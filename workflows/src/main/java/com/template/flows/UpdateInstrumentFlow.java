package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.workflows.flows.rpc.UpdateEvolvableToken;
import com.template.states.InstrumentState;
import jdk.nashorn.internal.ir.annotations.Immutable;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.InitiatingFlow;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.r3.corda.lib.tokens.workflows.utilities.QueryUtilities.heldTokenCriteria;

@InitiatingFlow
@StartableByRPC
public class UpdateInstrumentFlow extends FlowLogic<String> {

    final Amount<Currency> reSaleValuation;
    final int warranty;
    final String linearId;

    public UpdateInstrumentFlow(String linearId, Amount<Currency> reSaleValuation, int warranty) {
        this.linearId = linearId;
        this.reSaleValuation = reSaleValuation;
        this.warranty = warranty;
    }

    @Suspendable
    @Override
    public String call() throws FlowException {

        //InstrumentState instrumentState = stateStateAndRef.getState().getData();

        UUID instLinearId = UUID.fromString(linearId);

        QueryCriteria queryCriteria = new QueryCriteria.LinearStateQueryCriteria(null, ImmutableList.of(instLinearId), null, Vault.StateStatus.UNCONSUMED);

        List<StateAndRef<InstrumentState>> stateAndRefs = getServiceHub().getVaultService().
                queryBy(InstrumentState.class, queryCriteria).getStates();

        InstrumentState instrumentState;
        if (stateAndRefs.size() == 1) {
            instrumentState = stateAndRefs.get(0).getState().getData();

                if (!(instrumentState.getMaintainers().contains(getOurIdentity())))
                    throw new FlowException("You are not among the maintainer of the Instrument Token. ");

        }
        else
            throw new FlowException("More than one StateAndRef queried. ");

        InstrumentState updatedInstState = new InstrumentState(instrumentState.getLinearId(), instrumentState.getMaintainers() ,instrumentState.getName(), instrumentState.getYom(),
                 instrumentState.getBatchNo(), instrumentState.getValuation(), warranty, reSaleValuation);

        List<Party> partyList = instrumentState.getMaintainers();
        //partyList.add(getOurIdentity());

        subFlow(new UpdateEvolvableToken(stateAndRefs.get(0) , updatedInstState, partyList ));

        return " Instrument state updated. ";
    }
}
