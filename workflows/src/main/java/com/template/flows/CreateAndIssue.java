package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.template.states.InstrumentState;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.utilities.TransactionUtilitiesKt;
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens;
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.TransactionState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;

import java.util.Currency;
import java.util.UUID;

/**
 * Flow to create and issue house tokens. Token SDK provides some in-built flows which could be called to Create and Issue tokens.
 * This flow should be called by the issuer of the token. The constructor takes the owner and other properties of the house as
 * input parameters, it first creates the house token onto the issuer's ledger and then issues it to the owner.
 */
@StartableByRPC
public class CreateAndIssue extends FlowLogic<String> {

    private final Party owner;
    private final String name;
    private final int yom;
    private final String batchNo;
    private final Amount<Currency> valuation;
    private final int warranty;
    private final Amount<Currency> resaleValuation;

    public CreateAndIssue(Party owner, String name, int yom, String batchNo, Amount<Currency> valuation, int warranty,
                          Amount<Currency> resaleValuation) {
        this.owner = owner;
        this.name = name;
        this.yom = yom;
        this.batchNo = batchNo;
        this.valuation = valuation;
        this.warranty = warranty;
        this.resaleValuation = resaleValuation;
    }

    @Override
    @Suspendable
    public String call() throws FlowException {

        /* Choose the notary for the transaction */
        Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        /* Get a reference of own identity */
        Party issuer = getOurIdentity();

        /* Construct the output state */
        UniqueIdentifier uuid = UniqueIdentifier.Companion.fromString(UUID.randomUUID().toString());
        final InstrumentState houseState = new InstrumentState(uuid, ImmutableList.of(issuer),
                name, yom, batchNo, valuation, warranty, resaleValuation);

        /* Create an instance of TransactionState using the houseState token and the notary */
        TransactionState<InstrumentState> transactionState = new TransactionState<>(houseState, notary);

        /* Create the house token. Token SDK provides the CreateEvolvableTokens flow which could be called to create an
        evolvable token in the ledger.*/
        subFlow(new CreateEvolvableTokens(transactionState));

        /* Create an instance of IssuedTokenType, it is used by our Non-Fungible token which would be issued to the owner.
        Note that the IssuedTokenType takes a TokenPointer as an input, since EvolvableTokenType is not TokenType, but is
        a LinearState. This is done to separate the state info from the token so that the state can evolve independently.
        IssuedTokenType is a wrapper around the TokenType and the issuer. */
        IssuedTokenType issuedHouseToken = new IssuedTokenType(issuer, houseState.toPointer());

        /* Create an instance of the non-fungible house token with the owner as the token holder. The last parameter is a
        hash of the jar containing the TokenType, use the helper function to fetch it. */
        NonFungibleToken houseToken =
                new NonFungibleToken(issuedHouseToken, owner, UniqueIdentifier.Companion.fromString(UUID.randomUUID().toString()), TransactionUtilitiesKt.getAttachmentIdForGenericParam(houseState.toPointer()));

        /* Issue the house token by calling the IssueTokens flow provided with the TokenSDK */
        SignedTransaction stx = subFlow(new IssueTokens(ImmutableList.of(houseToken)));
        return "\nThe non-fungible house token is created with UUID: "+ uuid +". (This is what you will use in next step)"
                +"\nTransaction ID: "+stx.getId();

    }
}
