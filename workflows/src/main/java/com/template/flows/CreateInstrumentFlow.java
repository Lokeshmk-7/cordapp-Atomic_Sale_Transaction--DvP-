package com.template.flows;

import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens;
import com.template.states.InstrumentState;
import jdk.nashorn.internal.ir.annotations.Immutable;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.TransactionState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.InitiatingFlow;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;
import net.corda.core.node.ServiceHub;

import java.util.Currency;
import java.util.List;

@StartableByRPC
@InitiatingFlow
public class CreateInstrumentFlow extends FlowLogic<String> {

    private final String name;
    private final int yom;
    private final String batchNo;
    private final Amount<Currency> valuation;
    private final int warranty;
    private final Amount<Currency> resaleValuation;

    public CreateInstrumentFlow(String name, int yom, String batchNo, Amount<Currency> valuation, int warranty, Amount<Currency> resaleValuation) {
        this.name = name;
        this.yom = yom;
        this.batchNo = batchNo;
        this.valuation = valuation;
        this.warranty = warranty;
        this.resaleValuation = resaleValuation;
    }

    @Override
    public String call() throws FlowException {

        Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        Party issuer = getOurIdentity();

        UniqueIdentifier uniqueIdentifier = new UniqueIdentifier();

        InstrumentState instrumentState = new InstrumentState(uniqueIdentifier, ImmutableList.of(issuer), name, yom, batchNo, valuation, warranty, resaleValuation);

        TransactionState<InstrumentState> transactionState = new TransactionState<>(instrumentState, notary);

        subFlow(new CreateEvolvableTokens(transactionState));

        return "Instrument "+ this.name + " token created with batch no. " + this.batchNo + " and uuid " + uniqueIdentifier + " ." ;
    }
}
