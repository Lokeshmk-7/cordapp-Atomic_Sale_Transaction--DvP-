package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.money.*;
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
import net.corda.core.utilities.ProgressTracker;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.r3.corda.lib.tokens.workflows.utilities.QueryUtilities.heldTokenCriteria;

@InitiatingFlow
@StartableByRPC
public class SellInstrumentFlow extends FlowLogic<String> {

    private final String instrumentId;
    private final Party buyer;
    private final String issuedCurrency;



    private final ProgressTracker.Step CONSTRUCTOR_DONE = new ProgressTracker.Step("cONSTRUCTORE dONE");
    private final ProgressTracker.Step EXTRACTING_UUID = new ProgressTracker.Step("Extracting UUID from String id");
    private final ProgressTracker.Step GETTING_NFT = new ProgressTracker.Step("Getting Non-Fungible Token");
    private final ProgressTracker.Step GETTING_HOLDNFT = new ProgressTracker.Step("Getting Held Non-Fungible Token");
    private final ProgressTracker.Step START_BUYERSESSION = new ProgressTracker.Step("Starting Buyer Session");
    private final ProgressTracker.Step SENDING_NFT = new ProgressTracker.Step("Send NFT");
    private final ProgressTracker.Step SENDING_HOLDNFT = new ProgressTracker.Step("Sending Hold NFT");
    private final ProgressTracker.Step SENDING_ISSUEDCURRENCY = new ProgressTracker.Step("Sending issued currency");
    private final ProgressTracker.Step RECEIVED_INPUTTOKENS = new ProgressTracker.Step("Received Input tokens");
    private final ProgressTracker.Step RECEIVED_OUTPUTTOKENS = new ProgressTracker.Step("Received output tokens");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step SENDING_TRANSACTION = new ProgressTracker.Step("Sending the transaction");

    private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };

    // The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
    // checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call()
    // function.
    private final ProgressTracker progressTracker = new ProgressTracker(
            CONSTRUCTOR_DONE,
            EXTRACTING_UUID,
            GETTING_NFT,
            GETTING_HOLDNFT,
            START_BUYERSESSION,
            SENDING_NFT,
            SENDING_HOLDNFT,
            SENDING_ISSUEDCURRENCY,
            RECEIVED_INPUTTOKENS,
            RECEIVED_OUTPUTTOKENS,
            SIGNING_TRANSACTION,
            SENDING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
    );








    public SellInstrumentFlow(String instrumentId, Party buyer, String issuedCurrency) {
        this.instrumentId = instrumentId;
        this.buyer = buyer;
        this.issuedCurrency = issuedCurrency;
        progressTracker.setCurrentStep(CONSTRUCTOR_DONE);
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public String call() throws FlowException {

        progressTracker.setCurrentStep(EXTRACTING_UUID);

        /* Extracting the UUID (linear id) from the string instrumentId */
        UUID linearId = UUID.fromString(instrumentId);

        /* QueryCriteria to query the StateAndRef from UUID */
        QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(null,
                ImmutableList.of(linearId), null, Vault.StateStatus.UNCONSUMED);

        progressTracker.setCurrentStep(GETTING_NFT);

        /* Fetching the StateAndRef of Instrument using the query criteria */
        List<StateAndRef<InstrumentState>> instrumentStateStateAndRefList = getServiceHub().getVaultService().
                queryBy(InstrumentState.class, queryCriteria).getStates();
        if (instrumentStateStateAndRefList.size() != 1) throw new FlowException(" Non Fungible token not found");



        TokenPointer<InstrumentState> tokenPointer = instrumentStateStateAndRefList.get(0).getState().getData().toPointer();

        /* QueryCriteria to query the StateAndRef from UUID */
        QueryCriteria heldTokenCriteria = heldTokenCriteria(tokenPointer);

        progressTracker.setCurrentStep(GETTING_HOLDNFT);

        /* Fetching the StateAndRef of Instrument using the query criteria */
        final List<StateAndRef<NonFungibleToken>> ownedInstrumentTokensList = getServiceHub().getVaultService()
                .queryBy(NonFungibleToken.class, heldTokenCriteria).getStates();
        if (ownedInstrumentTokensList.size() != 1) throw new FlowException("Held Non Fungible token not found");

        progressTracker.setCurrentStep(START_BUYERSESSION);

        final FlowSession buyerSession = initiateFlow(buyer);

        progressTracker.setCurrentStep(SENDING_NFT);

        /* Send the StateAndRef and issuedCurrency of the Instrument to the Buyer so that he can extract the data earlier himself */
        subFlow(new SendStateAndRefFlow(buyerSession , instrumentStateStateAndRefList));

        progressTracker.setCurrentStep(SENDING_HOLDNFT);

        subFlow(new SendStateAndRefFlow(buyerSession, ownedInstrumentTokensList));

        progressTracker.setCurrentStep(SENDING_ISSUEDCURRENCY);

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

        progressTracker.setCurrentStep(RECEIVED_INPUTTOKENS);

        /* Check that none of the input tokens belong to us */
        final long myOwnCurrency = inputCurrencyTokens.stream()
                .filter(it -> it.getState().getData().getHolder().equals(getOurIdentity()))
                .count();
        if (myOwnCurrency != 0) throw new FlowException("Buyer sent us " + myOwnCurrency + " our own Token(s)");

        /* Receive the currency states that will go as outputs to the transaction */
        final List<FungibleToken> outputCurrencyTokens = buyerSession.receive(List.class).unwrap(it -> it);

        progressTracker.setCurrentStep(RECEIVED_OUTPUTTOKENS);

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

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);

        /* Signing the transaction and then sending it to buyer for his signature*/
        final SignedTransaction partiallySignedTransaction = getServiceHub().
                signInitialTransaction(transactionBuilder, getOurIdentity().getOwningKey());

        progressTracker.setCurrentStep(SENDING_TRANSACTION);

        final SignedTransaction fullySignedTransaction = subFlow(new CollectSignaturesFlow
                (partiallySignedTransaction, Collections.singletonList(buyerSession)));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);

        /* finalising the transaction and getting it notarised */
        final SignedTransaction notarisedTxn =
                subFlow(new FinalityFlow(fullySignedTransaction, Collections.singletonList(buyerSession)));

        /* Distributes the updates of the notarised txn to update the ledger */
        subFlow(new UpdateDistributionListFlow(notarisedTxn));

        return "Flow completed";

    }
}

