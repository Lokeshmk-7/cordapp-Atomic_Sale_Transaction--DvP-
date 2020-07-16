package com.template.states;

import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.template.contracts.InstrumentContract;
import net.corda.core.contracts.*;
import net.corda.core.identity.Party;
import org.jetbrains.annotations.NotNull;

import java.util.Currency;
import java.util.List;

@BelongsToContract(InstrumentContract.class)
public class InstrumentState extends EvolvableTokenType {

    private final UniqueIdentifier linearId;
    private final List<Party> maintainers;
    private final int fractionDigits = 0;
    private final Party issuer;

    private final String name;
    private final int yom;
    private final String batchNo;
    private final Amount<Currency> valuation;
    private final int warranty;
    private final Amount<Currency> resaleValuation;

    public InstrumentState(@NotNull UniqueIdentifier linearId,@NotNull List<Party> maintainers,@NotNull String name,@NotNull int yom,@NotNull String batchNo,@NotNull Amount<Currency> valuation,@NotNull int warranty,@NotNull Amount<Currency> resaleValuation) {
        this.linearId = linearId;
        this.maintainers = maintainers;
        this.issuer = maintainers.get(0);
        this.name = name;
        this.yom = yom;
        this.batchNo = batchNo;
        this.valuation = valuation;
        this.warranty = warranty;
        this.resaleValuation = resaleValuation;
    }

    public Party getIssuer() {
        return issuer;
    }

    public String getName() {
        return name;
    }

    public int getYom() {
        return yom;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public int getWarranty() {
        return warranty;
    }

    public Amount<Currency> getValuation() {
        return valuation;
    }

    public Amount<Currency> getResaleValuation() {
        return resaleValuation;
    }

    @Override
    public int getFractionDigits() {
        return fractionDigits;
    }

    @NotNull
    @Override
    public List<Party> getMaintainers() {
        return maintainers;
    }

    @NotNull
    @Override
    public UniqueIdentifier getLinearId() {
        return linearId;
    }

    /* This method returns a TokenPointer by using the linear Id of the evolvable state */
    public TokenPointer<InstrumentState> toPointer() {
        LinearPointer<InstrumentState> linearPointer = new LinearPointer<>(linearId, InstrumentState.class);
        return new TokenPointer<>(linearPointer, fractionDigits);
    }

}
