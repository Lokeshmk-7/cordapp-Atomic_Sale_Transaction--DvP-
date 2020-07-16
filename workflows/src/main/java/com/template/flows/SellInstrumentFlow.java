package com.template.flows;

import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.money.FiatCurrency;
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensUtilities;
import com.r3.corda.lib.tokens.workflows.internal.flows.distribution.UpdateDistributionListFlow;
import com.r3.corda.lib.tokens.workflows.types.PartyAndToken;
import com.template.states.InstrumentState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.RPCOps;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@InitiatingFlow
@StartableByRPC
public class SellInstrumentFlow extends FlowLogic<SignedTransaction> {

    private final String instrumentId;
    private final Party buyer;
    private final String issuedCurrency;

    public SellInstrumentFlow(String instrumentId, Party buyer, String issuedCurrency) {
        this.instrumentId = instrumentId;
        this.buyer = buyer;
        this.issuedCurrency = issuedCurrency;
    }

    @Override
    public SignedTransaction call() throws FlowException {

        /* Extracting the UUID (linear id) from the string instrumentId */
        UUID linearId = UUID.fromString(instrumentId);

        /* QueryCriteria to query the StateAndRef from UUID */
        QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(null,
                ImmutableList.of(linearId), null, Vault.StateStatus.UNCONSUMED);

        /* Fetching the StateAndRef of Instrument using the query criteria */
        List<StateAndRef<InstrumentState>> instrumentStateStateAndRefList = getServiceHub().getVaultService().
                queryBy(InstrumentState.class, queryCriteria).getStates();
        if (instrumentStateStateAndRefList.size() != 1) throw new FlowException(" Non Fungible token not found");

        final FlowSession buyerSession = initiateFlow(buyer);

        /* Send the StateAndRef and issuedCurrency of the Instrument to the Buyer so that he can extract the data earlier himself */
        subFlow(new SendStateAndRefFlow(buyerSession , instrumentStateStateAndRefList));

        TokenType tokenType = FiatCurrency.Companion.getInstance(issuedCurrency);
        final CordaX500Name x500Name = CordaX500Name.parse("O=CurrencyIssuer,L=New York,C=US");
        IssuedTokenType issuedCurrencyType = new IssuedTokenType( getServiceHub().getNetworkMapCache().getPeerByLegalName(x500Name), tokenType);
        buyerSession.send(issuedCurrencyType);

        /* Let's build the transaction until we receive inputs and outputs from Buyer */
        final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
        TransactionBuilder transactionBuilder = new TransactionBuilder(notary);

        InstrumentState instrumentState = instrumentStateStateAndRefList.get(0).getState().getData();

        MoveTokensUtilities.addMoveNonFungibleTokens(transactionBuilder, getServiceHub(),
                instrumentState.toPointer(), buyer);

        /* Receive the currency states that will go as inputs to the transaction */
        List<StateAndRef<FungibleToken>> inputCurrencyTokens = subFlow(new ReceiveStateAndRefFlow<FungibleToken>(buyerSession));

        /* Check that none of the input tokens belong to us */
        final long myOwnCurrency = inputCurrencyTokens.stream()
                .filter(it -> it.getState().getData().getHolder().equals(getOurIdentity()))
                .count();
        if (myOwnCurrency != 0) throw new FlowException("Buyer sent us " + myOwnCurrency + " our own Token(s)");

        /* Receive the currency states that will go as outputs to the transaction */
        final List<FungibleToken> outputCurrencyTokens = buyerSession.receive(List.class).unwrap(it -> it);

        /* Extracting the sum that will be paid by the buyer from output currency tokens */
        final long sumPaid = outputCurrencyTokens.stream()
                .filter(it -> it.getHolder().equals(getOurIdentity()))
                .map(FungibleToken::getAmount)
                .filter(it -> it.getToken().equals(issuedCurrencyType))
                .map(Amount::getQuantity)
                .reduce(0L, Math::addExact);

        /* Check to ensure the sum to be paid by buyer is more than or equal to the valuation price */
        final long price = instrumentState.getValuation().getQuantity();
        if (sumPaid < price)
        {
            throw new FlowException(" We were being paid only " + sumPaid + instrumentState.getValuation().getToken().getCurrencyCode()
                    + " instead of " + price + instrumentState.getValuation().getToken().getCurrencyCode());
        }

        /* Adding the input and output currency states to the MoveTokenUtilities*/
        MoveTokensUtilities.addMoveTokens(transactionBuilder, inputCurrencyTokens, outputCurrencyTokens);

        /* Signing the transaction and then sending it to buyer for his signature*/
        final SignedTransaction partiallySignedTransaction = getServiceHub().
                signInitialTransaction(transactionBuilder, getOurIdentity().getOwningKey());
        final SignedTransaction fullySignedTransaction = subFlow(new CollectSignaturesFlow
                (partiallySignedTransaction, Collections.singletonList(buyerSession)));

        /* finalising the transaction and getting it notarised */
        final SignedTransaction notarisedTxn =
                subFlow(new FinalityFlow(fullySignedTransaction, Collections.singletonList(buyerSession)));

        /* Distributes the updates of the notarised txn to update the ledger */
        subFlow(new UpdateDistributionListFlow(notarisedTxn));

        return notarisedTxn;

    }
}
