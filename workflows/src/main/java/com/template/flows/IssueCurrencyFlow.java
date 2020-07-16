package com.template.flows;


import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.contracts.utilities.TransactionUtilitiesKt;
import com.r3.corda.lib.tokens.money.FiatCurrency;
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens;
import jdk.nashorn.internal.parser.Token;
import net.corda.core.contracts.Amount;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.InitiatingFlow;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;

import java.util.Currency;

@InitiatingFlow
@StartableByRPC
public class IssueCurrencyFlow extends FlowLogic<String> {

    private final int amount;
    private final String currency;
    private final Party recipient;

    public IssueCurrencyFlow(int amount, String currency, Party recipient) {
        this.amount = amount;
        this.currency = currency;
        this.recipient = recipient;
    }

    @Override
    public String call() throws FlowException {

        Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        TokenType tokenType = FiatCurrency.Companion.getInstance(currency);

        IssuedTokenType issuedTokenType = new IssuedTokenType(getOurIdentity(), tokenType);

        FungibleToken fungibleToken = new FungibleToken( new Amount<>(amount, issuedTokenType), recipient, null);

        subFlow(new IssueTokens(ImmutableList.of(fungibleToken), ImmutableList.of(recipient)));

        return " " + this.amount + " " + this.currency + " are issued to the " + this.recipient + " ";
    }
}
