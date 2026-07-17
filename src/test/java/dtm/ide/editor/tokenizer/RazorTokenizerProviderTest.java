package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RazorTokenizerProviderTest {

    private final RazorTokenizerProvider tokenizer = new RazorTokenizerProvider();

    @Test
    void recognizesRazorDirectivesAndCodeBlocks() {
        Collection<Token> tokens = tokenizer.tokenize("""
                @page "/"
                @inject NavigationManager Nav

                @code {
                    private string Name = "Orion";
                }
                """, null);

        assertToken(tokens, "@page", TokenType.KEYWORD);
        assertToken(tokens, "@inject", TokenType.KEYWORD);
        assertToken(tokens, "@code", TokenType.KEYWORD);
        assertToken(tokens, "string", TokenType.KEYWORD);
        assertToken(tokens, "Name", CSharpTokenizerProvider.TOKEN_CLASS);
    }

    @Test
    void recognizesBlazorComponentsAndRazorAttributes() {
        Collection<Token> tokens = tokenizer.tokenize("""
                <InputText @bind-Value="Name" @onclick="Save" />
                <SurveyPrompt Title="@Title" />
                """, null);

        assertToken(tokens, "InputText", CSharpTokenizerProvider.TOKEN_CLASS);
        assertToken(tokens, "@bind-Value", CSharpTokenizerProvider.TOKEN_VARIABLE);
        assertToken(tokens, "@onclick", CSharpTokenizerProvider.TOKEN_VARIABLE);
        assertToken(tokens, "SurveyPrompt", CSharpTokenizerProvider.TOKEN_CLASS);
        assertToken(tokens, "@Title", CSharpTokenizerProvider.TOKEN_VARIABLE);
    }

    @Test
    void recognizesInlineAndParenthesizedExpressions() {
        Collection<Token> tokens = tokenizer.tokenize("""
                <p>Hello @Name</p>
                <p>@(DateTime.Now.Year)</p>
                @foreach (var item in Items) {
                    <span>@item</span>
                }
                """, null);

        assertToken(tokens, "@Name", CSharpTokenizerProvider.TOKEN_VARIABLE);
        assertToken(tokens, "DateTime", CSharpTokenizerProvider.TOKEN_CLASS);
        assertToken(tokens, "@foreach", TokenType.KEYWORD);
        assertToken(tokens, "var", TokenType.KEYWORD);
        assertToken(tokens, "Items", CSharpTokenizerProvider.TOKEN_CLASS);
    }

    private static void assertToken(Collection<Token> tokens, String text, String type) {
        assertTrue(tokens.stream().anyMatch(token -> text.equals(token.getText()) && type.equals(token.getType())),
                () -> "Expected token " + text + " with type " + type + " in " + tokens);
    }
}
