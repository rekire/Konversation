// This is a generated file. Not intended for manual editing.
package org.rewedigital.konversation.editor.psi;

import com.intellij.psi.PsiElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface KonversationIntentBlock extends PsiElement {

    @NotNull
    KonversationIntentDeclaration getIntentDeclaration();

    @Nullable
    KonversationPromptBlock getPromptBlock();

    @Nullable
    KonversationRepromptBlock getRepromptBlock();

    @NotNull
    KonversationSuggestionLine getSuggestionLine();

    @NotNull
    KonversationUtterancesBlock getUtterancesBlock();

}
