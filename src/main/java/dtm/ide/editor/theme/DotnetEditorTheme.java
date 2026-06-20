package dtm.ide.editor.theme;

import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.theme.EditorThemeConfig;
import dtm.ide.editor.tokenizer.CSharpTokenizerProvider;
import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

public final class DotnetEditorTheme implements EditorTheme {

    private static final Set<String> DOTNET_FILE_TYPES = Set.of(
            "cs", "csx", "cshtml", "razor"
    );

    @Override
    public EditorThemeConfig getConfigByFileType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return this;
        }

        String normalized = fileType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        return DOTNET_FILE_TYPES.contains(normalized) ? this : null;
    }

    @Override
    public Color getColorByToken(Token token) {
        if (token == null) {
            return null;
        }
        return getColorByToken(token.getType());
    }

    @Override
    public Color getColorByToken(String tokenType) {
        if (tokenType == null) {
            return null;
        }

        return switch (tokenType) {
            case CSharpTokenizerProvider.TOKEN_CLASS, CSharpTokenizerProvider.TOKEN_TYPE ->
                    new Color(78, 201, 176);
            case CSharpTokenizerProvider.TOKEN_VARIABLE ->
                    new Color(156, 220, 254);
            case CSharpTokenizerProvider.TOKEN_CONSTANT ->
                    new Color(181, 206, 168);
            case CSharpTokenizerProvider.TOKEN_FUNCTION ->
                    new Color(220, 220, 170);
            case CSharpTokenizerProvider.TOKEN_PREPROCESSOR ->
                    new Color(155, 155, 155);
            case TokenType.KEYWORD ->
                    new Color(86, 156, 214);
            case TokenType.STRING ->
                    new Color(206, 145, 120);
            case TokenType.NUMBER ->
                    new Color(181, 206, 168);
            case TokenType.COMMENT ->
                    new Color(106, 153, 85);
            default ->
                    null;
        };
    }
}
