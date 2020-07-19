package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.contracts.utilities.AmountUtilities;
import com.r3.corda.lib.tokens.selection.TokenQueryBy;
import com.r3.corda.lib.tokens.selection.database.selector.DatabaseTokenSelection;
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount;
import com.r3.corda.lib.tokens.workflows.utilities.QueryUtilities;
import com.template.states.InstrumentState;
import kotlin.Pair;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.r3.corda.lib.tokens.selection.database.config.DatabaseSelectionConfigKt.RETRY_SLEEP_DEFAULT;
import static com.r3.corda.lib.tokens.selection.database.config.DatabaseSelectionConfigKt.RETRY_CAP_DEFAULT;
import static com.r3.corda.lib.tokens.selection.database.config.DatabaseSelectionConfigKt.PAGE_SIZE_DEFAULT;
import static com.r3.corda.lib.tokens.selection.database.config.DatabaseSelectionConfigKt.MAX_RETRIES_DEFAULT;

@InitiatedBy(SellInstrumentFlow.class)
public class BuyInstrumentFlow extends FlowLogic<SignedTransaction> {

    private final FlowSession SellerSession;




    private final ProgressTracker.Step GETTING_NFT = new ProgressTracker.Step("Getting Non-Fungible Token from seller");
    private final ProgressTracker.Step GETTING_HOLDNFT = new ProgressTracker.Step("Getting Held Non-Fungible Token by seller");
    private final ProgressTracker.Step GETTING_ISSUEDCURRENCY = new ProgressTracker.Step("Getting issued currency");
    private final ProgressTracker.Step QUERYING_TOKENS = new ProgressTracker.Step("Starting Buyer Session");
    private final ProgressTracker.Step SENDING_INPUTFT = new ProgressTracker.Step("Send Input FT");
    private final ProgressTracker.Step SENDING_OUTPUTFT = new ProgressTracker.Step("Sending Output NFT");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step SENDING_TRANSACTION = new ProgressTracker.Step("Signing the transaction");

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
            GETTING_NFT,
            GETTING_HOLDNFT,
            GETTING_ISSUEDCURRENCY,
            QUERYING_TOKENS,
            SENDING_INPUTFT,
            SENDING_OUTPUTFT,
            SIGNING_TRANSACTION,
            FINALISING_TRANSACTION
    );






    public BuyInstrumentFlow(FlowSession sellerSession) {
        this.SellerSession = sellerSession;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {

        progressTracker.setCurrentStep(GETTING_NFT);

        /* Recieve the list of Instrument StateAndRef sent from Seller */
        final List<StateAndRef<InstrumentState>> instrumentStateStateAndRefList =
                subFlow(new ReceiveStateAndRefFlow<>(SellerSession));

        /* Checking size of instrumentStateStateAndRefList to ensure only one StateAndRef was received */
        if (instrumentStateStateAndRefList.size() != 1)
            throw new FlowException(" Received multiple instrument type (StateAndRef)");

        progressTracker.setCurrentStep(GETTING_HOLDNFT);

        /* Recieve the list of Instrument StateAndRef sent from Seller that he owns */
        final List<StateAndRef<NonFungibleToken>> ownedinstrumentStateStateAndRefList =
                subFlow(new ReceiveStateAndRefFlow<>(SellerSession));

        /* Checking size of instrumentStateStateAndRefList to ensure only one StateAndRef was received */
        if (ownedinstrumentStateStateAndRefList.size() != 1)
            throw new FlowException(" Received multiple owned instrument type (StateAndRef)");

        /* Checking that the stateAndRef received for both instrument and owned instrument are the same */
        if (!(instrumentStateStateAndRefList.get(0).getState().getData().getLinearId()
                .equals(( (TokenPointer<InstrumentState>) ownedinstrumentStateStateAndRefList.get(0).getState().getData().getTokenType()).getPointer().getPointer() )))
            throw new FlowException(" Received instrument and held instrument sent by user do not match");

        InstrumentState instrumentState = instrumentStateStateAndRefList.get(0).getState().getData();

        final long price = instrumentState.getResaleValuation().getQuantity();

        progressTracker.setCurrentStep(GETTING_ISSUEDCURRENCY);

        final IssuedTokenType issuedCurrency = SellerSession.receive(IssuedTokenType.class).unwrap(it -> it);

        /* Assembling the currency states */
        QueryCriteria heldByme = QueryUtilities
                .heldTokenAmountCriteria(issuedCurrency.getTokenType(), getOurIdentity());

        QueryCriteria properlyIssued = QueryUtilities
                .tokenAmountWithIssuerCriteria(issuedCurrency.getTokenType(), issuedCurrency.getIssuer());

        /* Have the utility do the "dollars to cents" conversion for us */
        final Amount<TokenType> priceInCurrency = AmountUtilities.amount(price, issuedCurrency.getTokenType());

        /* Generate the buyer's currency inputs, to be spent, and the outputs, the currency tokens that will be
         held by Alice */
        final DatabaseTokenSelection tokenSelection = new DatabaseTokenSelection(
                getServiceHub(), 8, RETRY_SLEEP_DEFAULT, RETRY_CAP_DEFAULT, PAGE_SIZE_DEFAULT);

        progressTracker.setCurrentStep(QUERYING_TOKENS);

        final Pair<List<StateAndRef<FungibleToken>>, List<FungibleToken>> inputsAndOutputs;
        inputsAndOutputs = tokenSelection.generateMove(
                Collections.singletonList(new kotlin.Pair<>(SellerSession.getCounterparty(), priceInCurrency)),
                getOurIdentity(),
                new TokenQueryBy(issuedCurrency.getIssuer(), it-> true, heldByme.and(properlyIssued)),
                getRunId().getUuid());

        progressTracker.setCurrentStep(SENDING_INPUTFT);

        subFlow(new SendStateAndRefFlow(SellerSession, inputsAndOutputs.getFirst()));

        progressTracker.setCurrentStep(SENDING_OUTPUTFT);

        SellerSession.send(inputsAndOutputs.getSecond());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);

        final SecureHash SignedTxn = subFlow(new SignTransactionFlow(SellerSession){
            @Override
            protected void checkTransaction(@NotNull SignedTransaction stx) throws FlowException {

            }
        } ).getId();

        return subFlow(new ReceiveFinalityFlow(SellerSession, SignedTxn));
    }
}
