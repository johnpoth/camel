/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.core.commands.edit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.github.cameltooling.lsp.internal.CamelLanguageServer;
import com.github.cameltooling.lsp.internal.CamelTextDocumentService;
import com.github.cameltooling.lsp.internal.diagnostic.CamelKModelineDiagnosticService;
import com.github.cameltooling.lsp.internal.diagnostic.ConfigurationPropertiesDiagnosticService;
import com.github.cameltooling.lsp.internal.diagnostic.ConnectedModeDiagnosticService;
import com.github.cameltooling.lsp.internal.diagnostic.EndpointDiagnosticService;
import com.github.cameltooling.lsp.internal.settings.SettingsManager;
import org.apache.camel.catalog.ConfigurationPropertiesValidationResult;
import org.apache.camel.catalog.EndpointValidationResult;
import org.apache.camel.parser.model.CamelEndpointDetails;
import org.apache.camel.tooling.util.Strings;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jline.builtins.ConfigurationPath;
import org.jline.builtins.Nano;
import org.jline.builtins.Options;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import static org.jline.keymap.KeyMap.alt;
import static org.jline.keymap.KeyMap.ctrl;

public class CamelNanoLspEditor extends Nano {
    private boolean insertHelp = false;
    private boolean help = false;
    private Box suggestionBox;
    private List<AttributedString> suggestions;
    private List<List<AttributedString>> documentation;
    private final LocalCamelDocumentService camelDocumentService = new LocalCamelDocumentService();

    public CamelNanoLspEditor(Terminal terminal, Path root, Options opts, ConfigurationPath configPath) {
        super(terminal, root, opts, configPath);
        mouseSupport = true;
        this.camelDocumentService.init();
        keys.unbind(ctrl(' '));
        keys.unbind(alt(' '));
        keys.bind(CamelLspOperation.LSP_SUGGESTION, ctrl(' '));
    }

    @Override
    protected Terminal.MouseTracking getMouseTracking() {
        return Terminal.MouseTracking.Any;
    }

    @Override
    protected void preDisplayNewLines() {
        if (insertHelp) {
            insertHelp();
            resetSuggestion();
        }
    }

    @Override
    protected boolean handle(Operations op) {
        if (op == null) {
            return false;
        }
        if (op instanceof Operation) {
            return handle((Operation) op);
        } else if (op instanceof CamelLspOperation) {
            return handle((CamelLspOperation) op);
        }
        return false;
    }

    protected boolean handle(Operation op) {
        switch (op) {
            case UP:
                if (help && suggestionBox != null) {
                    suggestionBox.up();
                    return true;
                }
                break;
            case DOWN:
                if (help && suggestionBox != null) {
                    suggestionBox.down();
                    return true;
                }
                break;
            case LEFT:
            case RIGHT:
                if (help) {
                    resetSuggestion();
                }
                break;
            case INSERT:
                if (this.help) {
                    this.insertHelp = true;
                    return true;
                }
                break;
            case QUIT:
                if (help) {
                    resetSuggestion();
                    return true;
                }
                break;
            default:
                return false;
        }
        return false;
    }

    protected boolean handle(CamelLspOperation op) {
        if (op == CamelLspOperation.LSP_SUGGESTION) {
            help = true;
            return true;
        }
        return false;
    }

    @Override
    protected List<AttributedString> postDisplayNewLines(List<AttributedString> newLines) {
        // show diagnostics if any
        String fileName = buffer.getFile();
        StringBuilder text = new StringBuilder();
        for (String line : this.buffer.getLines()) {
            text.append(line);
            text.append('\n');
        }
        TextDocumentItem textDocumentItem = new TextDocumentItem(fileName, CamelLanguageServer.LANGUAGE_ID, 0, text.toString());
        List<Diagnostic> diagnostics = camelDocumentService.computeDiagnostic(textDocumentItem);
        for (Diagnostic diagnostic : diagnostics) {
            Range range = diagnostic.getRange();
            Position start = range.getStart();
            Position end = range.getEnd();
            //TODO when they aren't on the same line
            if (start.getLine() == end.getLine()) {
                int line = end.getLine() - buffer.getFirstLineToDisplay();
                AttributedString attributedString = newLines.get(line);
                AttributedStringBuilder builder = new AttributedStringBuilder(attributedString.length());
                builder.append(attributedString.subSequence(0, start.getCharacter()));
                builder.append(attributedString.subSequence(start.getCharacter(), end.getCharacter()),
                        AttributedStyle.DEFAULT.underline().foreground(AttributedStyle.RED));
                builder.append(attributedString.subSequence(end.getCharacter(), attributedString.length()));
                newLines.set(line, builder.toAttributedString());
                if (line == getMouseY() - 1 && getMouseX() >= start.getCharacter()
                        && getMouseX() <= end.getCharacter()) {
                    String message = diagnostic.getMessage();
                    if (Strings.isEmpty(message)) {
                        continue;
                    }
                    try {
                        // sometimes the message is URL encoded
                        message = java.net.URLDecoder.decode(message, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        //ignore
                    }
                    // remove trailing carriage returns
                    message = message.replaceAll("\\s+$", "");
                    // build tool tip box
                    int xi = start.getCharacter();
                    int dBoxSize = message.length() + 2;
                    int maxWidth = (int) Math.round((size.getColumns() - xi) * 0.60); // let's do 60% of what's left of the screen
                    int xl = Math.min(dBoxSize + xi, xi + maxWidth);
                    //adjust content
                    List<AttributedString> boxLines = adjustLines(
                            Collections.singletonList(new AttributedString(message)), dBoxSize - 2, xl - xi - 2);
                    if (!Strings.isEmpty(message)) {
                        // TODO: display above current pos if there is no space at the bottom
                        Box box = new Box(xi, start.getLine() + 1, xl, start.getLine() + boxLines.size() + 2);
                        box.setLines(boxLines);
                        addBoxBorders(newLines, box);
                        addBoxLines(box, newLines);
                    }
                }
            }
        }
        if (help) {
            showCompletion(newLines);
        }
        return newLines;
    }

    private void resetSuggestion() {
        this.suggestions = null;
        this.documentation = null;
        this.suggestionBox = null;
        this.insertHelp = false;
        this.help = false;
    }

    private void insertHelp() {
        String fileName = buffer.getFile();
        StringBuilder text = new StringBuilder();
        for (String line : this.buffer.getLines()) {
            text.append(line);
            text.append('\n');
        }
        // TODO store completions so we don't recompute them when inserting
        TextDocumentItem textDocumentItem = new TextDocumentItem(fileName, CamelLanguageServer.LANGUAGE_ID, 0, text.toString());
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> eitherCompletableFuture = this.camelDocumentService
                .completionLocal(textDocumentItem,
                        new Position(buffer.getLine(), buffer.getOffsetInLine() + buffer.getColumn()));
        List<CompletionItem> lines;
        try {
            lines = eitherCompletableFuture.get().getLeft();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        CompletionItem item = lines.get(this.suggestionBox.getSelected());
        Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
        if (textEdit != null) {
            TextEdit left = textEdit.getLeft();
            Range range = left.getRange();
            Position start = range.getStart();
            int endLine = start.getLine();
            Position end = range.getEnd();
            int startLine = end.getLine();
            if (startLine == endLine) {
                StringBuilder newLine = new StringBuilder();
                String line = this.buffer.getLines().get(startLine);
                newLine.append(line, 0, start.getCharacter());
                newLine.append(left.getNewText());
                newLine.append(line.substring(end.getCharacter()));
                this.buffer.getLines().set(startLine, newLine.toString());
                this.buffer.moveRight(left.getNewText().length());
            } else {
                // first line
                StringBuilder newFirstLine = new StringBuilder();
                String oldFirstLine = this.buffer.getLines().get(startLine);
                String newText = left.getNewText();
                newFirstLine.append(oldFirstLine, 0, start.getCharacter());
                newFirstLine.append(newText, 0, oldFirstLine.length() - start.getCharacter());
                this.buffer.getLines().set(startLine, newFirstLine.toString());
                // second line
                StringBuilder newSecondLine = new StringBuilder();
                String oldSecondLine = this.buffer.getLines().get(endLine);
                newSecondLine.append(newText, oldFirstLine.length() - start.getCharacter(), end.getCharacter());
                newSecondLine.append(oldSecondLine, end.getCharacter(), end.getCharacter());
                this.buffer.getLines().set(endLine, newSecondLine.toString());
                this.buffer.moveRight(left.getNewText().length());
            }
        } else if (!Strings.isEmpty(item.getInsertText()) || !Strings.isEmpty(item.getLabel())) {
            String insert = Strings.isEmpty(item.getInsertText()) ? item.getLabel() : item.getInsertText();
            StringBuilder newLine = new StringBuilder();
            String line = this.buffer.getLines().get(buffer.getLine());
            newLine.append(line, 0, buffer.getOffsetInLine() + buffer.getColumn());
            newLine.append(insert);
            this.buffer.getLines().set(buffer.getLine(), newLine.toString());
            this.buffer.moveRight(insert.length());
        }
        this.buffer.setDirty(true);
    }

    private void showCompletion(List<AttributedString> newLines) {
        if (this.documentation == null || this.suggestions == null) {
            initDocLines();
        }
        if (suggestions.isEmpty()) {
            resetSuggestion();
            return;
        }
        initBoxes(newLines);
    }

    private void initDocLines() {
        this.suggestions = new ArrayList<>();
        this.documentation = new ArrayList<>();
        String fileName = buffer.getFile();
        StringBuilder text = new StringBuilder();
        for (String line : this.buffer.getLines()) {
            text.append(line);
            text.append('\n');
        }
        TextDocumentItem textDocumentItem = new TextDocumentItem(fileName, CamelLanguageServer.LANGUAGE_ID, 0, text.toString());
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> eitherCompletableFuture = this.camelDocumentService
                .completionLocal(textDocumentItem,
                        new Position(buffer.getLine(), buffer.getOffsetInLine() + buffer.getColumn()));
        if (eitherCompletableFuture.isCompletedExceptionally()) {
            return;
        }
        try {
            List<CompletionItem> left = eitherCompletableFuture.get().getLeft();
            AttributedStyle typeStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA + AttributedStyle.BRIGHT);
            for (CompletionItem item : left) {
                List<AttributedString> docs = new ArrayList<>();
                String type = item.getDetail();
                if (!Strings.isEmpty(type)) {
                    docs.add(new AttributedString(type, typeStyle));
                    docs.add(new AttributedString(""));
                }
                Either<String, MarkupContent> docMap = item.getDocumentation();
                if (docMap != null) {
                    String doc = docMap.getLeft();
                    if (!Strings.isEmpty(doc)) {
                        docs.add(new AttributedString(doc));
                    }
                }
                if (!docs.isEmpty()) {
                    documentation.add(docs);
                }
                suggestions.add(new AttributedString(item.getLabel()));
            }
        } catch (Exception e) {
            // ignore
            // TODO: show exception message in help
        }
    }

    private void initBoxes(List<AttributedString> screenLines) {
        // TODO: when there is no space on the right, build boxes to the left of current pos
        // TODO: when there is no space on the bottom, build boxes to the top of current pos
        if (suggestionBox == null) {
            suggestionBox = buildSuggestionBox(suggestions, screenLines);
        }
        addBoxBorders(screenLines, suggestionBox);
        addBoxLines(suggestionBox, screenLines);
        if (!documentation.isEmpty()) {
            Box documentationBox = buildDocumentationBox(screenLines, suggestionBox);
            addBoxBorders(screenLines, documentationBox);
            addBoxLines(documentationBox, screenLines);
        }
    }

    private Box buildSuggestionBox(List<AttributedString> suggestions, List<AttributedString> screenLines) {
        // calculate width
        int dXi = buffer.getColumn() - 3;
        int xi = Math.max(printLineNumbers ? 9 : 1, dXi);
        int maxSuggestionLength = suggestions.stream().mapToInt(AttributedString::length).max().getAsInt() + 2;
        int screenWidth = (int) Math.round((size.getColumns() - xi) * 0.60); // let's do 60% of what's left of the screen
        int xl = Math.min(maxSuggestionLength + xi, xi + screenWidth);

        // calculate height
        int maxHeight = screenLines.size() - 1;
        int yi = buffer.getLine() + 1;

        // build suggestion box
        int yl = Math.min(maxHeight, yi + suggestions.size() + 1);
        Box box = new Box(xi, yi, xl, yl);
        box.setLines(suggestions);
        box.setSelectedStyle(AttributedStyle.DEFAULT.background(AttributedStyle.BLUE));
        return box;
    }

    private Box buildDocumentationBox(List<AttributedString> screenLines, Box suggestionBox) {
        List<AttributedString> documentation = this.documentation.get(suggestionBox.getSelected());
        //calculate width
        int dXi = suggestionBox.xl;
        int dBoxSize = documentation.stream().mapToInt(AttributedString::length).max().getAsInt() + 2;
        int xi = Math.max(printLineNumbers ? 9 : 1, dXi);
        int maxWidth = (int) Math.round((size.getColumns() - xi) * 0.60); // let's do 60% of what's left of the screen
        int xl = Math.min(dBoxSize + xi, xi + maxWidth);
        //adjust content
        documentation = adjustLines(documentation, dBoxSize - 2, xl - xi - 2);
        // calculate height
        int height = screenLines.size();
        int yi = suggestionBox.yi + suggestionBox.getSelectedInView();
        int yl = yi + documentation.size() + 1;
        if (yl > height - 1) {
            yl = Math.min(height - 1, yi + 2);
            yi = Math.max(1, yl - documentation.size() - 1);
        }
        //create documentation box
        Box documentationBox = new Box(xi, yi, xl, yl);
        documentationBox.setLines(documentation);
        documentationBox.setSelectedStyle(AttributedStyle.DEFAULT);
        return documentationBox;
    }

    private void addBoxLines(Box box, List<AttributedString> screenLines) {
        for (int i = 0; i < box.visibleLines.size(); i++) {
            AttributedStringBuilder line = new AttributedStringBuilder(box.xl - box.xi - 2);
            AttributedStyle background = AttributedStyle.DEFAULT;
            if (i == box.getSelectedInView()) {
                background = box.getSelectedStyle();
            }
            line.append(box.visibleLines.get(i), background);
            line.style(background);
            line.append(' ', box.xl - box.xi - line.length() - 2);
            setLineInBox(screenLines, box, box.yi + 1 + i, line.toAttributedString(), false);
        }
    }

    private List<AttributedString> adjustLines(List<AttributedString> lines, int max, int boxLength) {
        if (max <= boxLength) {
            return lines;
        }
        List<AttributedString> adjustedLines = new ArrayList<>();
        for (AttributedString line : lines) {
            if (line.length() < boxLength) {
                adjustedLines.add(line);
            } else {
                int start = 0;
                while (start < line.length()) {
                    int stepSize = Math.min(start + boxLength, line.length());
                    int end = stepSize;
                    // check last line
                    if (end - start >= boxLength) {
                        // let's not cutoff in the middle of a word.
                        while (end > start && !Character.isWhitespace(line.charAt(end - 1))) {
                            end--;
                        }
                    }
                    if (end == start) {
                        // there was no space, let's not loop forever
                        end = stepSize;
                    }
                    adjustedLines.add(line.substring(start, end));
                    start = end;
                }
            }
        }
        return adjustedLines;
    }

    private void addBoxBorders(List<AttributedString> newLines, Box box) {
        int width = box.xl - box.xi;
        AttributedStringBuilder top = new AttributedStringBuilder(width);
        top.append('┌');
        top.append('─', width - 2);
        top.append('┐');
        setLineInBox(newLines, box, box.yi, top.toAttributedString(), true);
        AttributedStringBuilder sides = new AttributedStringBuilder(width);
        sides.append('│');
        sides.append(' ', width - 2);
        sides.append('│');
        AttributedString side = sides.toAttributedString();
        for (int y = box.yi + 1; y < box.yl; y++) {
            setLineInBox(newLines, box, y, side, true);
        }
        AttributedStringBuilder bottom = new AttributedStringBuilder(width);
        bottom.append('└');
        bottom.append('─', width - 2);
        bottom.append('┘');
        setLineInBox(newLines, box, box.yl, bottom.toAttributedString(), true);
    }

    private void setLineInBox(List<AttributedString> newLines, Box box, int y, AttributedString line, boolean borders) {
        int start = box.xi;
        int end = box.xl;
        if (!borders) {
            start++;
            end--;
        }
        AttributedString currLine = newLines.get(y);
        AttributedStringBuilder newLine = new AttributedStringBuilder(Math.max(end, currLine.length()) + 1);
        int currLength = currLine.length();
        // add carriage return at the end of the line
        if (currLine.charAt(currLength - 1) == '\n') {
            currLength -= 1;
        }
        newLine.append(currLine, 0, Math.min(start, currLength));
        newLine.append(' ', start - currLength);
        newLine.append(line);
        newLine.append(currLine, start + line.length(), currLength);
        if (currLength == currLine.length() - 1) {
            newLine.append('\n');
        }
        newLines.set(y, newLine.toAttributedString());
    }

    //y axis  (xi,yi)┌──────────────────────────────┐(xl,yi)
    //               │                              │
    //               │                              │
    //               │                              │
    //               │                              │
    //               │                              │
    //               │                              │
    //               │                              │
    //        (xi,yl)└──────────────────────────────┘(xl,yl)
    //                           x axis
    private static class Box {
        // (xi,yi) upper left
        // (xl,yi) upper right
        // (xi,yl) lower left
        // (xl,yl) lower right
        private final int xi, xl, yi, yl;
        private List<AttributedString> lines;
        private int selected = 0;
        private int selectedInView = 0;
        private final int size;
        private AttributedStyle selectedStyle = AttributedStyle.DEFAULT;
        private List<AttributedString> visibleLines;

        private Box(int xi, int yi, int xl, int yl) {
            this.xi = xi;
            this.yi = yi;
            this.xl = xl;
            this.yl = yl;
            this.size = yl - yi - 1;
        }

        private void setLines(List<AttributedString> lines) {
            this.lines = lines;
            this.visibleLines = lines.subList(0, size);
        }

        private int getSelected() {
            return selected;
        }

        private void setSelectedStyle(AttributedStyle selectedStyle) {
            this.selectedStyle = selectedStyle;
        }

        private AttributedStyle getSelectedStyle() {
            return selectedStyle;
        }

        private void down() {
            selected = Math.floorMod(selected + 1, lines.size());
            if (!scrollable() || selectedInView < size - 1) {
                selectedInView++;
                return;
            }
            // calculate new view
            if (selected == 0) {
                // return to the beginning of list
                selectedInView = 0;
                visibleLines = lines.subList(0, size);
            } else {
                visibleLines = lines.subList(selected - size + 1, selected + 1);
            }
        }

        private void up() {
            selected = Math.floorMod(selected - 1, lines.size());
            if (!scrollable() || selectedInView > 0) {
                selectedInView--;
                return;
            }
            // calculate new view
            if (selected == lines.size() - 1) {
                // last element in list, return to beginning
                this.selectedInView = this.size - 1;
                this.visibleLines = this.lines.subList(this.lines.size() - size, this.lines.size());
            } else {
                this.visibleLines = this.lines.subList(selected, selected + size);
            }
        }

        private boolean scrollable() {
            return this.size < this.lines.size();
        }

        private int getSelectedInView() {
            return Math.floorMod(selectedInView, lines.size());
        }
    }

    private static class LocalCamelDocumentService extends CamelTextDocumentService {
        private EndpointDiagnosticService endpointDiagnosticService;
        private ConfigurationPropertiesDiagnosticService configurationPropertiesDiagnosticService;
        private CamelKModelineDiagnosticService camelKModelineDiagnosticService;
        private ConnectedModeDiagnosticService connectedModeDiagnosticService;

        private LocalCamelDocumentService() {
            super(null);
        }

        private void init() {
            endpointDiagnosticService = new EndpointDiagnosticService(getCamelCatalog());
            configurationPropertiesDiagnosticService = new ConfigurationPropertiesDiagnosticService(getCamelCatalog());
            camelKModelineDiagnosticService = new CamelKModelineDiagnosticService();
            connectedModeDiagnosticService = new ConnectedModeDiagnosticService();
        }

        private CompletableFuture<Either<List<CompletionItem>, CompletionList>> completionLocal(
                TextDocumentItem textDocument, Position position) {
            String uri = textDocument.getUri();
            openedDocuments.put(uri, textDocument);
            return super.completion(new CompletionParams(new TextDocumentIdentifier(uri), position));
        }

        @Override
        public SettingsManager getSettingsManager() {
            return new SettingsManager(this);
        }

        private List<Diagnostic> computeDiagnostic(TextDocumentItem documentItem) {
            String uri = documentItem.getUri();
            String camelText = documentItem.getText();
            Map<CamelEndpointDetails, EndpointValidationResult> endpointErrors
                    = endpointDiagnosticService.computeCamelEndpointErrors(camelText, uri);
            List<Diagnostic> diagnostics
                    = endpointDiagnosticService.converToLSPDiagnostics(camelText, endpointErrors, documentItem);
            Map<String, ConfigurationPropertiesValidationResult> configurationPropertiesErrors
                    = configurationPropertiesDiagnosticService.computeCamelConfigurationPropertiesErrors(camelText, uri);
            diagnostics.addAll(configurationPropertiesDiagnosticService.converToLSPDiagnostics(configurationPropertiesErrors));
            diagnostics.addAll(camelKModelineDiagnosticService.compute(camelText, documentItem));
            diagnostics.addAll(connectedModeDiagnosticService.compute(camelText, documentItem));
            diagnostics.addAll(connectedModeDiagnosticService.compute(camelText, documentItem));
            return diagnostics;
        }
    }

    protected enum CamelLspOperation implements Operations {
        LSP_SUGGESTION
    }
}
