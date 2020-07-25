package com.template.flows;

import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.r3.corda.lib.tokens.contracts.utilities.TransactionUtilitiesKt;
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens;
import com.template.states.InstrumentState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.InitiatingFlow;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;

import java.util.Currency;

@StartableByRPC
@InitiatingFlow
public class IssueInstrumentFlow extends FlowLogic<String> {

    private final Party owner;
    private final String name;

    public IssueInstrumentFlow(String name, Party owner) {
        this.owner = owner;
        this.name = name;
    }

    @Override
    public String call() throws FlowException {

        StateAndRef<InstrumentState> instrumentStateStateAndRef = getServiceHub().getVaultService().
                queryBy(InstrumentState.class).getStates().stream()
                .filter(sf -> sf.getState().getData().getName().equals(this.name)).findAny()
                .orElseThrow(() -> new IllegalArgumentException("Instrument of name " + this.name + " not found"));

        InstrumentState instrumentState = instrumentStateStateAndRef.getState().getData();

        TokenPointer<InstrumentState> tokenPointer = instrumentState.toPointer();

        IssuedTokenType issuedTokenType = new IssuedTokenType(getOurIdentity(), tokenPointer);

        NonFungibleToken nonFungibleToken = new NonFungibleToken(issuedTokenType, owner, new UniqueIdentifier(), TransactionUtilitiesKt.getAttachmentIdForGenericParam(tokenPointer));

        SignedTransaction signedTransaction =  subFlow(new IssueTokens(ImmutableList.of(nonFungibleToken)));

        return "The instrument " + this.name + " is issued to " + this.owner + ".";
    }
}
