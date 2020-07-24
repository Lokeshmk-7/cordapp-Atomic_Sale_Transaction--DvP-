package com.template.contracts;

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract;
import com.template.states.InstrumentState;
import net.corda.core.contracts.Contract;
import net.corda.core.contracts.ContractState;
import net.corda.core.transactions.LedgerTransaction;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class InstrumentContract extends EvolvableTokenContract implements Contract {

    @Override
    public void additionalCreateChecks(@NotNull LedgerTransaction tx) {

        List<ContractState> outputState = tx.getOutputStates();
        List<ContractState> inputState = tx.getInputStates();

        requireThat(req -> {

            req.using("Inputs while creating instrument must be 0.",inputState.size() == 0);
            req.using("Outputs while creating instrument must be 1", outputState.size() == 1);
            req.using("Output state while creating instrument must be of type InstrumentState", outputState.get(0) instanceof InstrumentState);

            InstrumentState instrumentState = (InstrumentState) outputState.get(0);
            LocalDate localDate = LocalDate.now();
            int currentYear = localDate.getYear();
            req.using("yom(Year of Manufacture) cannot be greater than current year", currentYear >= instrumentState.getYom());
            req.using("Valuation while creatio must be greater than 0.", instrumentState.getValuation().getQuantity() > 0);
            req.using("Years of Warranty must be more than 0.", instrumentState.getWarranty() > 0);
            req.using("Resale valuation must be equal to valuation while creating instrument", instrumentState.getValuation().getQuantity() == instrumentState.getResaleValuation().getQuantity());

            if (! (tx.getCommand(0).getSigners().contains(((InstrumentState) outputState.get(0)).getIssuer().getOwningKey())) )
                throw new IllegalArgumentException("Instrument issuer must be a signer");

            return null;
        });

    }

    @Override
    public void additionalUpdateChecks(@NotNull LedgerTransaction tx) {

        List<ContractState> outputState = tx.getOutputStates();
        List<ContractState> inputState = tx.getInputStates();

        requireThat(req -> {

            req.using("Inputs while updating instrument must be 1.",inputState.size() == 1);
            req.using("Outputs while creating instrument must be 1", outputState.size() == 1);
            req.using("Output state while creating instrument must be of type InstrumentState", outputState.get(0) instanceof InstrumentState);

            InstrumentState instrumentState = (InstrumentState) outputState.get(0);
            LocalDate localDate = LocalDate.now();
            int currentYear = localDate.getYear();
            req.using("Valuation while creatio must be greater than 0.", instrumentState.getValuation().getQuantity() > 0);
            req.using("Resale Valuation while creatio must be greater than 0.", instrumentState.getResaleValuation().getQuantity() > 0);

            if (! (tx.getCommand(0).getSigners().contains(((InstrumentState) outputState.get(0)).getIssuer().getOwningKey())) )
                throw new IllegalArgumentException("Instrument issuer must be a signer");

            return null;
        });
    }
}
