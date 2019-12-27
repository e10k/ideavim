/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2019 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim;

import com.intellij.ide.DataManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.ActionPlan;
import com.intellij.openapi.editor.actionSystem.DocCommandGroupId;
import com.intellij.openapi.editor.actionSystem.TypedActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.maddyhome.idea.vim.action.change.VimRepeater;
import com.maddyhome.idea.vim.action.macro.ToggleRecordingAction;
import com.maddyhome.idea.vim.action.motion.search.SearchEntryFwdAction;
import com.maddyhome.idea.vim.action.motion.search.SearchEntryRevAction;
import com.maddyhome.idea.vim.command.*;
import com.maddyhome.idea.vim.extension.VimExtensionHandler;
import com.maddyhome.idea.vim.group.ChangeGroup;
import com.maddyhome.idea.vim.group.RegisterGroup;
import com.maddyhome.idea.vim.group.visual.VimSelection;
import com.maddyhome.idea.vim.group.visual.VisualGroupKt;
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase;
import com.maddyhome.idea.vim.helper.*;
import com.maddyhome.idea.vim.key.*;
import com.maddyhome.idea.vim.listener.SelectionVimListenerSuppressor;
import com.maddyhome.idea.vim.listener.VimListenerSuppressor;
import com.maddyhome.idea.vim.option.OptionsManager;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.intellij.openapi.actionSystem.CommonDataKeys.*;
import static com.intellij.openapi.actionSystem.PlatformDataKeys.PROJECT_FILE_DIRECTORY;

/**
 * This handlers every keystroke that the user can argType except those that are still valid hotkeys for various Idea
 * actions. This is a singleton.
 */
public class KeyHandler {
  /**
   * Returns a reference to the singleton instance of this class
   *
   * @return A reference to the singleton
   */
  @NotNull
  public static KeyHandler getInstance() {
    if (instance == null) {
      instance = new KeyHandler();
    }
    return instance;
  }

  /**
   * Creates an instance
   */
  private KeyHandler() {
  }

  /**
   * Sets the original key handler
   *
   * @param origHandler The original key handler
   */
  public void setOriginalHandler(TypedActionHandler origHandler) {
    this.origHandler = origHandler;
  }

  /**
   * Gets the original key handler
   *
   * @return The original key handler
   */
  public TypedActionHandler getOriginalHandler() {
    return origHandler;
  }

  public static void executeVimAction(@NotNull Editor editor,
                                      @NotNull EditorActionHandlerBase cmd,
                                      DataContext context) {
    CommandProcessor.getInstance()
      .executeCommand(editor.getProject(), () -> cmd.execute(editor, getProjectAwareDataContext(editor, context)),
                      cmd.getId(), DocCommandGroupId.noneGroupId(editor.getDocument()), UndoConfirmationPolicy.DEFAULT,
                      editor.getDocument());
  }

  /**
   * Execute an action
   *
   * @param action  The action to execute
   * @param context The context to run it in
   */
  public static boolean executeAction(@NotNull AnAction action, @NotNull DataContext context) {
    final AnActionEvent event =
      new AnActionEvent(null, context, ActionPlaces.ACTION_SEARCH, action.getTemplatePresentation(),
                        ActionManager.getInstance(), 0);

    if (action instanceof ActionGroup && !((ActionGroup)action).canBePerformed(context)) {
      // Some of the AcitonGroups should not be performed, but shown as a popup
      ListPopup popup = JBPopupFactory.getInstance()
        .createActionGroupPopup(event.getPresentation().getText(), (ActionGroup)action, context, false, null, -1);

      Component component = context.getData(PlatformDataKeys.CONTEXT_COMPONENT);
      if (component != null) {
        Window window = SwingUtilities.getWindowAncestor(component);
        if (window != null) {
          popup.showInCenterOf(window);
        }
        return true;
      }
      popup.showInFocusCenter();
      return true;
    }
    else {
      // beforeActionPerformedUpdate should be called to update the action. It fixes some rider-specific problems
      //   because rider use async update method. See VIM-1819
      action.beforeActionPerformedUpdate(event);
      if (event.getPresentation().isEnabled()) {
        action.actionPerformed(event);
        return true;
      }
    }
    return false;
  }

  public void startDigraphSequence(@NotNull Editor editor) {
    final CommandState editorState = CommandState.getInstance(editor);
    editorState.startDigraphSequence();
  }

  public void startLiteralSequence(@NotNull Editor editor) {
    final CommandState editorState = CommandState.getInstance(editor);
    editorState.startLiteralSequence();
  }

  /**
   * This is the main key handler for the Vim plugin. Every keystroke not handled directly by Idea is sent here for
   * processing.
   *
   * @param editor  The editor the key was typed into
   * @param key     The keystroke typed by the user
   * @param context The data context
   */
  public void handleKey(@NotNull Editor editor, @NotNull KeyStroke key, @NotNull DataContext context) {
    handleKey(editor, key, context, true);
  }

  /**
   * Invoked before acquiring a write lock and actually handling the keystroke.
   * <p>
   * Drafts an optional {@link ActionPlan} that will be used as a base for zero-latency rendering in editor.
   *
   * @param editor  The editor the key was typed into
   * @param key     The keystroke typed by the user
   * @param context The data context
   * @param plan    The current action plan
   */
  public void beforeHandleKey(@NotNull Editor editor,
                              @NotNull KeyStroke key,
                              @NotNull DataContext context,
                              @NotNull ActionPlan plan) {

    final CommandState.Mode mode = CommandState.getInstance(editor).getMode();

    if (mode == CommandState.Mode.INSERT || mode == CommandState.Mode.REPLACE) {
      VimPlugin.getChange().beforeProcessKey(editor, context, key, plan);
    }
  }

  public void handleKey(@NotNull Editor editor,
                        @NotNull KeyStroke key,
                        @NotNull DataContext context,
                        boolean allowKeyMappings) {
    VimPlugin.clearError();
    // All the editor actions should be performed with top level editor!!!
    // Be careful: all the EditorActionHandler implementation should correctly process InjectedEditors
    editor = HelperKt.getTopLevelEditor(editor);
    final CommandState editorState = CommandState.getInstance(editor);

    // If this is a "regular" character keystroke, get the character
    char chKey = key.getKeyChar() == KeyEvent.CHAR_UNDEFINED ? 0 : key.getKeyChar();

    final boolean isRecording = editorState.isRecording();
    boolean shouldRecord = true;

    if (allowKeyMappings && handleKeyMapping(editor, key, context)) {
      if (!editorState.isOperatorPending() || editorState.peekCommandArgumentType() != Argument.Type.OFFSETS) {
        return;
      }
    }
    else if (isCommandCount(chKey, editorState)) {
      editorState.setCount((editorState.getCount() * 10) + (chKey - '0'));
    }
    else if (isDeleteCommandCount(key, editorState)) {
      editorState.setCount(editorState.getCount() / 10);
    }
    else if (isEditorReset(key, editorState)) {
      handleEditorReset(editor, key, context, editorState);
    }
    // If we got this far the user is entering a command or supplying an argument to an entered command.
    // First let's check to see if we are at the point of expecting a single character argument to a command.
    else if (editorState.getCurrentArgumentType() == Argument.Type.CHARACTER) {
      handleCharArgument(key, chKey, editorState);
    }
    // If we are this far, then the user must be entering a command or a non-single-character argument
    // to an entered command. Let's figure out which it is
    else {
      // For debugging purposes we track the keys entered for this command
      editorState.addKey(key);

      if (handleDigraph(editor, key, context, editorState)) return;

      // Ask the key/action tree if this is an appropriate key at this point in the command and if so,
      // return the node matching this keystroke
      Node node = editorState.getCurrentNode().get(key);
      node = mapOpCommand(key, node, editorState);

      if (node instanceof CommandNode) {
        handleCommandNode(editor, context, key, (CommandNode) node, editorState);
      }
      else if (node instanceof CommandPartNode) {
        editorState.setCurrentNode((CommandPartNode) node);
      }
      else {
        // If we are in insert/replace mode send this key in for processing
        if (editorState.getMode() == CommandState.Mode.INSERT || editorState.getMode() == CommandState.Mode.REPLACE) {
          if (!VimPlugin.getChange().processKey(editor, context, key)) {
            shouldRecord = false;
          }
        }
        else if (editorState.getMode() == CommandState.Mode.SELECT) {
          if (!VimPlugin.getChange().processKeyInSelectMode(editor, context, key)) {
            shouldRecord = false;
          }
        }
        else if (editorState.getMappingMode() == MappingMode.CMD_LINE) {
          if (!VimPlugin.getProcess().processExKey(editor, key)) {
            shouldRecord = false;
          }
        }
        // If we get here then the user has entered an unrecognized series of keystrokes
        else {
          editorState.setCommandState(CurrentCommandState.BAD_COMMAND);
        }

        partialReset(editor);
      }
    }

    // Do we have a fully entered command at this point? If so, lets execute it
    if (editorState.getCommandState() == CurrentCommandState.READY) {
      executeCommand(editor, key, context, editorState);
    }
    else if (editorState.getCommandState() == CurrentCommandState.BAD_COMMAND) {
      if (editorState.getMappingMode() == MappingMode.OP_PENDING) {
        editorState.popModes();
      }
      VimPlugin.indicateError();
      reset(editor);
    }
    else if (isRecording && shouldRecord) {
      VimPlugin.getRegister().recordKeyStroke(key);
    }
  }

  /**
   * See the description for {@link com.maddyhome.idea.vim.action.DuplicableOperatorAction}
   */
  private Node mapOpCommand(KeyStroke key, Node node, @NotNull CommandState editorState) {
    if (editorState.isDuplicateOperatorKeyStroke(key)) {
      return editorState.getCurrentNode().get(KeyStroke.getKeyStroke('_'));
    }
    return node;
  }

  private static <T> boolean isPrefix(@NotNull List<T> list1, @NotNull List<T> list2) {
    if (list1.size() > list2.size()) {
      return false;
    }
    for (int i = 0; i < list1.size(); i++) {
      if (!list1.get(i).equals(list2.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void handleEditorReset(@NotNull Editor editor, @NotNull KeyStroke key, @NotNull final DataContext context, CommandState editorState) {
    if (editorState.isDefaultState()) {
      RegisterGroup register = VimPlugin.getRegister();
      if (register.getCurrentRegister() == register.getDefaultRegister()) {
        if (key.getKeyCode() == KeyEvent.VK_ESCAPE) {
          CommandProcessor.getInstance()
            .executeCommand(editor.getProject(), () -> KeyHandler.executeAction("EditorEscape", context), "", null);
        }
        VimPlugin.indicateError();
      }
    }
    reset(editor);
    ChangeGroup.resetCaret(editor, false);
  }

  private boolean handleKeyMapping(@NotNull final Editor editor,
                                   @NotNull final KeyStroke key,
                                   @NotNull final DataContext context) {

    final CommandState commandState = CommandState.getInstance(editor);
    final MappingState mappingState = commandState.getMappingState();

    if (commandState.getCommandState() == CurrentCommandState.CHAR_OR_DIGRAPH
      || isBuildingMultiKeyCommand(commandState)
      || isMappingDisabledForKey(key, commandState)) {
      return false;
    }

    mappingState.stopMappingTimer();

    // Save the unhandled key strokes until we either complete or abandon the sequence.
    mappingState.addKey(key);

    final KeyMapping mapping = VimPlugin.getKey().getKeyMapping(commandState.getMappingMode());

    // Returns true if any of these methods handle the key. False means that the key is unrelated to mapping and should
    // be processed as normal.
    return handleUnfinishedMappingSequence(editor, mappingState, mapping)
      || handleCompleteMappingSequence(editor, context, commandState, mappingState, mapping, key)
      || handleAbandonedMappingSequence(editor, mappingState, context);
  }

  private boolean isBuildingMultiKeyCommand(CommandState commandState) {
    // Don't apply mapping if we're in the middle of building a multi-key command.
    // E.g. given nmap s v, don't try to map <C-W>s to <C-W>v
    //   Similarly, nmap <C-W>a <C-W>s should not try to map the second <C-W> in <C-W><C-W>
    // Note that we might still be at RootNode if we're handling a prefix, because we might be buffering keys until we
    // get a match. This means we'll still process the rest of the keys of the prefix.
    return !(commandState.getCurrentNode() instanceof RootNode);
  }

  private boolean isMappingDisabledForKey(@NotNull KeyStroke key, @NotNull CommandState commandState) {
    // "0" can be mapped, but the mapping isn't applied when entering a count. Other digits are always mapped, even when
    // entering a count.
    // See `:help :map-modes`
    return key.getKeyChar() == '0' && commandState.getCount() > 0;
  }

  private boolean handleUnfinishedMappingSequence(@NotNull Editor editor,
                                                  @NotNull MappingState mappingState,
                                                  @NotNull KeyMapping mapping) {
    // Is there at least one mapping that starts with the current sequence? This does not include complete matches,
    // unless a sequence is also a prefix for another mapping. We eagerly evaluate the shortest mapping, so even if a
    // mapping is a prefix, it will get evaluated when the next character is entered.
    // Note that currentlyUnhandledKeySequence is the same as the state after commandState.getMappingKeys().add(key). It
    // would be nice to tidy ths up
    if (!mapping.isPrefix(mappingState.getKeys())) {
      return false;
    }

    // If the timeout option is set, set a timer that will abandon the sequence and replay the unhandled keys unmapped.
    // Every time a key is pressed and handled, the timer is stopped. E.g. if there is a mapping for "dweri", and the
    // user has typed "dw" wait for the timeout, and then replay "d" and "w" without any mapping (which will of course
    // delete a word)
    final Application application = ApplicationManager.getApplication();
    if (!application.isUnitTestMode() && OptionsManager.INSTANCE.getTimeout().isSet()) {
      mappingState.startMappingTimer(actionEvent -> application.invokeLater(() -> {

        final List<KeyStroke> unhandledKeys = mappingState.detachKeys();

        // TODO: I'm not sure why we abandon plugin commands here
        // Would be useful to have a comment or a helpfully named helper method here
        if (editor.isDisposed() || unhandledKeys.get(0).equals(StringHelper.PlugKeyStroke)) {
          return;
        }

        for (KeyStroke keyStroke : unhandledKeys) {
          handleKey(editor, keyStroke, new EditorDataContext(editor), false);
        }
      }, ModalityState.stateForComponent(editor.getComponent())));
    }

    return true;
  }

  private boolean handleCompleteMappingSequence(@NotNull Editor editor,
                                                @NotNull DataContext context,
                                                @NotNull CommandState commandState,
                                                @NotNull MappingState mappingState,
                                                @NotNull KeyMapping mapping,
                                                KeyStroke key) {

    // The current sequence isn't a prefix, check to see if it's a completed sequence.
    final MappingInfo currentMappingInfo = mapping.get(mappingState.getKeys());
    MappingInfo mappingInfo = currentMappingInfo;
    if (mappingInfo == null) {
      // It's an abandoned sequence, check to see if the previous sequence was a complete sequence.
      // TODO: This is incorrect behaviour
      // What about sequences that were completed N keys ago?
      // This should really be handled as part of an abandoned key sequence. We should also consolidate the replay
      // of cached keys - this happens in timeout, here and also in abandoned sequences.
      // Extract most of this method into handleMappingInfo. If we have a complete sequence, call it and we're done.
      // If it's not a complete sequence, handleAbandonedMappingSequence should do something like call
      // mappingState.detachKeys and look for the longest complete sequence in the returned list, evaluate it, and then
      // replay any keys not yet handled. NB: The actual implementation should be compared to Vim behaviour to see what
      // should actually happen.
      final ArrayList<KeyStroke> previouslyUnhandledKeySequence = new ArrayList<>();
      mappingState.getKeys().forEach(previouslyUnhandledKeySequence::add);
      if (previouslyUnhandledKeySequence.size() > 1) {
        previouslyUnhandledKeySequence.remove(previouslyUnhandledKeySequence.size() - 1);
        mappingInfo = mapping.get(previouslyUnhandledKeySequence);
      }
    }

    if (mappingInfo == null) {
      return false;
    }

    mappingState.clearKeys();

    final EditorDataContext currentContext = new EditorDataContext(editor);

    final List<KeyStroke> toKeys = mappingInfo.getToKeys();
    final VimExtensionHandler extensionHandler = mappingInfo.getExtensionHandler();

    if (toKeys != null) {
      final boolean fromIsPrefix = isPrefix(mappingInfo.getFromKeys(), toKeys);
      boolean first = true;
      for (KeyStroke keyStroke : toKeys) {
        final boolean recursive = mappingInfo.isRecursive() && !(first && fromIsPrefix);
        handleKey(editor, keyStroke, currentContext, recursive);
        first = false;
      }
    }
    else if (extensionHandler != null) {
      final CommandProcessor processor = CommandProcessor.getInstance();

      // Cache isOperatorPending in case the extension changes the mode while moving the caret
      // See CommonExtensionTest
      // TODO: Is this legal? Should we assert in this case?
      final boolean shouldCalculateOffsets = commandState.isOperatorPending();

      Map<Caret, Integer> startOffsets =
        editor.getCaretModel().getAllCarets().stream().collect(Collectors.toMap(Function.identity(), Caret::getOffset));

      if (extensionHandler.isRepeatable()) {
        VimRepeater.Extension.INSTANCE.clean();
      }

      processor.executeCommand(editor.getProject(), () -> extensionHandler.execute(editor, context),
        "Vim " + extensionHandler.getClass().getSimpleName(), null);

      if (extensionHandler.isRepeatable()) {
        VimRepeater.Extension.INSTANCE.setLastExtensionHandler(extensionHandler);
        VimRepeater.Extension.INSTANCE.setArgumentCaptured(null);
        VimRepeater.INSTANCE.setRepeatHandler(true);
      }

      if (shouldCalculateOffsets && !commandState.hasCommandArgument()) {
        Map<Caret, VimSelection> offsets = new HashMap<>();

        for (Caret caret : editor.getCaretModel().getAllCarets()) {
          @Nullable Integer startOffset = startOffsets.get(caret);
          if (caret.hasSelection()) {
            final VimSelection vimSelection = VimSelection.Companion
              .create(UserDataManager.getVimSelectionStart(caret), caret.getOffset(),
                SelectionType.fromSubMode(CommandStateHelper.getSubMode(editor)), editor);
            offsets.put(caret, vimSelection);
            commandState.popModes();
          }
          else if (startOffset != null && startOffset != caret.getOffset()) {
            // Command line motions are always characterwise exclusive
            int endOffset = caret.getOffset();
            if (startOffset < endOffset) {
              endOffset -= 1;
            } else {
              startOffset -= 1;
            }
            final VimSelection vimSelection = VimSelection.Companion
              .create(startOffset, endOffset, SelectionType.CHARACTER_WISE, editor);
            offsets.put(caret, vimSelection);

            try (VimListenerSuppressor.Locked ignored = SelectionVimListenerSuppressor.INSTANCE.lock()) {
              // Move caret to the initial offset for better undo action
              //  This is not a necessary thing, but without it undo action look less convenient
              editor.getCaretModel().moveToOffset(startOffset);
            }
          }
        }

        if (!offsets.isEmpty()) {
          commandState.setCommandArgument(new Argument(offsets));
          commandState.setCommandState(CurrentCommandState.READY);
        }
      }
    }

    // If we've just evaluated the previous key sequence, make sure to also handle the current key
    if (mappingInfo != currentMappingInfo) {
      handleKey(editor, key, currentContext, true);
    }

    return true;
  }

  private boolean handleAbandonedMappingSequence(@NotNull Editor editor,
                                                 @NotNull MappingState mappingState,
                                                 DataContext context) {

    // The user has terminated a mapping sequence with an unexpected key
    // E.g. if there is a mapping for "hello" and user enters command "help" the processing of "h", "e" and "l" will be
    //   prevented by this handler. Make sure the currently unhandled keys are processed as normal.

    final List<KeyStroke> unhandledKeyStrokes = mappingState.detachKeys();

    // If there is only the current key to handle, do nothing
    if (unhandledKeyStrokes.size() == 1) {
      return false;
    }

    // Okay, look at the code below. Why is the first key handled separately?
    // Let's assume the next mappings:
    //   - map ds j
    //   - map I 2l
    // If user enters `dI`, the first `d` will be caught be this handler because it's a prefix for `ds` command.
    //  After the user enters `I`, the caught `d` should be processed without mapping and the rest of keys
    //  should be processed with mappings (to make I work)
    //
    // Additionally, the <Plug>mappings are not executed if the are failed to map to somethings.
    //   E.g.
    //   - map <Plug>iA  someAction
    //   - map I <Plug>i
    //   For `IA` someAction should be executed.
    //   But if the user types `Ib`, `<Plug>i` won't be executed again. Only `b` will be passed to keyHandler.

    if (unhandledKeyStrokes.get(0).equals(StringHelper.PlugKeyStroke)) {
      handleKey(editor, unhandledKeyStrokes.get(unhandledKeyStrokes.size() - 1), context, true);
    } else {
      handleKey(editor, unhandledKeyStrokes.get(0), context, false);

      for (KeyStroke keyStroke : unhandledKeyStrokes.subList(1, unhandledKeyStrokes.size())) {
        handleKey(editor, keyStroke, context, true);
      }
    }

    return true;
  }

  private boolean isDeleteCommandCount(@NotNull KeyStroke key, @NotNull CommandState editorState) {
    // See `:help N<Del>`
    return (editorState.getMode() == CommandState.Mode.COMMAND || editorState.getMode() == CommandState.Mode.VISUAL) &&
           editorState.getCommandState() == CurrentCommandState.NEW_COMMAND &&
           editorState.getCurrentArgumentType() != Argument.Type.CHARACTER &&
           editorState.getCurrentArgumentType() != Argument.Type.DIGRAPH &&
           key.getKeyCode() == KeyEvent.VK_DELETE &&
           editorState.getCount() != 0;
  }

  private boolean isEditorReset(@NotNull KeyStroke key, @NotNull CommandState editorState) {
    return (editorState.getMode() == CommandState.Mode.COMMAND) && StringHelper.isCloseKeyStroke(key);
  }

  private void handleCharArgument(@NotNull KeyStroke key, char chKey, @NotNull CommandState commandState) {
    // We are expecting a character argument - is this a regular character the user typed?
    // Some special keys can be handled as character arguments - let's check for them here.
    if (chKey == 0) {
      switch (key.getKeyCode()) {
        case KeyEvent.VK_TAB:
          chKey = '\t';
          break;
        case KeyEvent.VK_ENTER:
          chKey = '\n';
          break;
      }
    }

    if (chKey != 0) {
      // Create the character argument, add it to the current command, and signal we are ready to process the command
      commandState.setCommandArgument(new Argument(chKey));
      commandState.setCommandState(CurrentCommandState.READY);
    }
    else {
      // Oops - this isn't a valid character argument
      commandState.setCommandState(CurrentCommandState.BAD_COMMAND);
    }
  }

  private boolean isCommandCount(char chKey, @NotNull CommandState editorState) {
    return (editorState.getMode() == CommandState.Mode.COMMAND || editorState.getMode() == CommandState.Mode.VISUAL) &&
           editorState.getCommandState() == CurrentCommandState.NEW_COMMAND &&
           editorState.getCurrentArgumentType() != Argument.Type.CHARACTER &&
           editorState.getCurrentArgumentType() != Argument.Type.DIGRAPH &&
           Character.isDigit(chKey) &&
           (editorState.getCount() != 0 || chKey != '0');
  }

  private boolean handleDigraph(@NotNull Editor editor,
                                @NotNull KeyStroke key,
                                @NotNull DataContext context,
                                @NotNull CommandState editorState) {

    // Support starting a digraph/literal sequence if the operator accepts one as an argument, e.g. 'r' or 'f'.
    // Normally, we start the sequence (in Insert or CmdLine mode) through a VimAction that can be mapped. Our
    // VimActions don't work as arguments for operators, so we have to special case here. Helpfully, Vim appears to
    // hardcode the shortcuts, and doesn't support mapping, so everything works nicely.
    if (editorState.getCurrentArgumentType() == Argument.Type.DIGRAPH) {
      if (DigraphSequence.isDigraphStart(key)) {
        editorState.startDigraphSequence();
        return true;
      }
      if (DigraphSequence.isLiteralStart(key)) {
        editorState.startLiteralSequence();
        return true;
      }
    }

    DigraphResult res = editorState.processDigraphKey(key, editor);
    switch (res.getResult()) {
      case DigraphResult.RES_HANDLED:
      case DigraphResult.RES_BAD:
        return true;

      case DigraphResult.RES_DONE:
        if (editorState.getCurrentArgumentType() == Argument.Type.DIGRAPH) {
          editorState.setCurrentArgumentType(Argument.Type.CHARACTER);
        }
        final KeyStroke stroke = res.getStroke();
        if (stroke == null) {
          return false;
        }
        handleKey(editor, stroke, context);
        return true;

      case DigraphResult.RES_UNHANDLED:
        if (editorState.getCurrentArgumentType() == Argument.Type.DIGRAPH) {
          editorState.setCurrentArgumentType(Argument.Type.CHARACTER);
          handleKey(editor, key, context);
          return true;
        }
        return false;
    }

    return false;
  }

  private void executeCommand(@NotNull Editor editor,
                              @NotNull KeyStroke key,
                              @NotNull DataContext context,
                              @NotNull CommandState editorState) {
    // Let's go through the command stack and merge it all into one command. At this time there should never
    // be more than two commands on the stack - one is the actual command and the other would be a motion
    // command argument needed by the first command
    final Command cmd = editorState.buildCommand();

    // If we have a command and a motion command argument, both could possibly have their own counts. We
    // need to adjust the counts so the motion gets the product of both counts and the count associated with
    // the command gets reset. Example 3c2w (change 2 words, three times) becomes c6w (change 6 words)
    final Argument arg = cmd.getArgument();
    if (arg != null && arg.getType() == Argument.Type.MOTION) {
      final Command mot = arg.getMotion();
      // If no count was entered for either command then nothing changes. If either had a count then
      // the motion gets the product of both.
      int cnt = cmd.getRawCount() == 0 && mot.getRawCount() == 0 ? 0 : cmd.getCount() * mot.getCount();
      mot.setCount(cnt);
      cmd.setCount(0);
    }

    // If we were in "operator pending" mode, reset back to normal mode.
    if (editorState.getMappingMode() == MappingMode.OP_PENDING) {
      editorState.popModes();
    }

    // Save off the command we are about to execute
    editorState.setCommand(cmd);

    Project project = editor.getProject();
    final Command.Type type = cmd.getType();
    if (type.isWrite() && !editor.getDocument().isWritable()) {
      VimPlugin.indicateError();
      reset(editor);
    }

    if (!cmd.getFlags().contains(CommandFlags.FLAG_TYPEAHEAD_SELF_MANAGE)) {
      IdeEventQueue.getInstance().flushDelayedKeyEvents();
    }

    if (ApplicationManager.getApplication().isDispatchThread()) {
      Runnable action = new ActionRunner(editor, context, cmd, key);
      EditorActionHandlerBase cmdAction = cmd.getAction();
      String name = cmdAction.getId();

      if (type.isWrite()) {
        RunnableHelper.runWriteCommand(project, action, name, action);
      }
      else if (type.isRead()) {
        RunnableHelper.runReadCommand(project, action, name, action);
      }
      else {
        CommandProcessor.getInstance().executeCommand(project, action, name, action);
      }
    }
  }

  private void handleCommandNode(Editor editor,
                                 DataContext context,
                                 KeyStroke key,
                                 @NotNull CommandNode node,
                                 CommandState editorState) {
    // The user entered a valid command. Create the command and add it to the stack
    EditorActionHandlerBase action = node.getActionHolder().getAction();
    editorState.pushNewCommand(action);

    if (editorState.getCurrentArgumentType() != null && !checkArgumentCompatibility(node, editorState)) return;

    if (action.getArgumentType() == null || stopMacroRecord(node, editorState)) {
      editorState.setCommandState(CurrentCommandState.READY);
    }
    else {
      editorState.setCurrentArgumentType(action.getArgumentType());
      startWaitingForArgument(editor, context, key.getKeyChar(), editorState.getCurrentArgumentType(), editorState);
      partialReset(editor);
    }

    // TODO In the name of God, get rid of EX_STRING, FLAG_COMPLETE_EX and all the related staff
    if (editorState.getCurrentArgumentType() == Argument.Type.EX_STRING && action.getFlags().contains(CommandFlags.FLAG_COMPLETE_EX)) {
      if (VimPlugin.getProcess().isForwardSearch()) {
        action = new SearchEntryFwdAction();
      }
      else {
        action = new SearchEntryRevAction();
      }

      String text = VimPlugin.getProcess().endSearchCommand(editor);
      editorState.popCommand();
      editorState.pushNewCommand(action);
      editorState.setCommandArgument(new Argument(text));
      editorState.popModes();
    }
  }

  private boolean stopMacroRecord(CommandNode node, @NotNull CommandState editorState) {
    return editorState.isRecording() && node.getActionHolder().getAction() instanceof ToggleRecordingAction;
  }

  private void startWaitingForArgument(Editor editor,
                                       DataContext context,
                                       char key,
                                       @NotNull Argument.Type argument,
                                       CommandState editorState) {
    switch (argument) {
      case CHARACTER:
      case DIGRAPH:
        editorState.setCommandState(CurrentCommandState.CHAR_OR_DIGRAPH);
        break;
      case MOTION:
        if (editorState.isDotRepeatInProgress() && VimRepeater.Extension.INSTANCE.getArgumentCaptured() != null) {
          editorState.setCommandArgument(VimRepeater.Extension.INSTANCE.getArgumentCaptured());
          editorState.setCommandState(CurrentCommandState.READY);
        }
        editorState.pushModes(editorState.getMode(), editorState.getSubMode(), MappingMode.OP_PENDING);
        break;
      case EX_STRING:
        VimPlugin.getProcess().startSearchCommand(editor, context, editorState.getCount(), key);
        editorState.setCommandState(CurrentCommandState.NEW_COMMAND);
        editorState.pushModes(CommandState.Mode.CMD_LINE, CommandState.SubMode.NONE, MappingMode.CMD_LINE);
        editorState.popCommand();
    }
  }

  private boolean checkArgumentCompatibility(@NotNull CommandNode node, @NotNull CommandState editorState) {
    if (editorState.getCurrentArgumentType() == Argument.Type.MOTION &&
        node.getActionHolder().getAction().getType() != Command.Type.MOTION) {
      editorState.setCommandState(CurrentCommandState.BAD_COMMAND);
      return false;
    }
    return true;
  }

  /**
   * Execute an action by name
   *
   * @param name    The name of the action to execute
   * @param context The context to run it in
   */
  public static boolean executeAction(@NotNull String name, @NotNull DataContext context) {
    ActionManager aMgr = ActionManager.getInstance();
    AnAction action = aMgr.getAction(name);
    return action != null && executeAction(action, context);
  }

  /**
   * Partially resets the state of this handler. Resets the command count, clears the key list, resets the key tree
   * node to the root for the current mode we are in.
   *
   * @param editor The editor to reset.
   */
  public void partialReset(@Nullable Editor editor) {
    CommandState editorState = CommandState.getInstance(editor);
    editorState.setCount(0);
    editorState.getMappingState().reset();
    editorState.getKeys().clear();
    editorState.setCurrentNode(VimPlugin.getKey().getKeyRoot(editorState.getMappingMode()));
  }

  /**
   * Resets the state of this handler. Does a partial reset then resets the mode, the command, and the argument
   *
   * @param editor The editor to reset.
   */
  public void reset(@Nullable Editor editor) {
    partialReset(editor);
    CommandState editorState = CommandState.getInstance(editor);
    editorState.clearCommands();
    editorState.setCommandState(CurrentCommandState.NEW_COMMAND);
    editorState.setCurrentArgumentType(null);
  }

  /**
   * Completely resets the state of this handler. Resets the command mode to normal, resets, and clears the selected
   * register.
   *
   * @param editor The editor to reset.
   */
  public void fullReset(@Nullable Editor editor) {
    VimPlugin.clearError();
    CommandState.getInstance(editor).reset();
    reset(editor);
    VimPlugin.getRegister().resetRegister();
    if (editor != null) {
      VisualGroupKt.updateCaretState(editor);
      editor.getSelectionModel().removeSelection();
    }
  }

  // This method is copied from com.intellij.openapi.editor.actionSystem.EditorAction.getProjectAwareDataContext
  @NotNull
  private static DataContext getProjectAwareDataContext(@NotNull final Editor editor,
                                                        @NotNull final DataContext original) {
    if (PROJECT.getData(original) == editor.getProject()) {
      return new DialogAwareDataContext(original);
    }

    return dataId -> {
      if (PROJECT.is(dataId)) {
        final Project project = editor.getProject();
        if (project != null) {
          return project;
        }
      }
      return original.getData(dataId);
    };

  }

  // This class is copied from com.intellij.openapi.editor.actionSystem.DialogAwareDataContext.DialogAwareDataContext
  private final static class DialogAwareDataContext implements DataContext {
    private static final DataKey[] keys = {PROJECT, PROJECT_FILE_DIRECTORY, EDITOR, VIRTUAL_FILE, PSI_FILE};
    private final Map<String, Object> values = new HashMap<>();

    DialogAwareDataContext(DataContext context) {
      for (DataKey key : keys) {
        values.put(key.getName(), key.getData(context));
      }
    }

    @Nullable
    @Override
    public Object getData(@NotNull @NonNls String dataId) {
      if (values.containsKey(dataId)) {
        return values.get(dataId);
      }
      final Editor editor = (Editor)values.get(EDITOR.getName());
      if (editor != null) {
        return DataManager.getInstance().getDataContext(editor.getContentComponent()).getData(dataId);
      }
      return null;
    }
  }

  /**
   * This was used as an experiment to execute actions as a runnable.
   */
  static class ActionRunner implements Runnable {
    @Contract(pure = true)
    ActionRunner(Editor editor, DataContext context, Command cmd, KeyStroke key) {
      this.editor = editor;
      this.context = context;
      this.cmd = cmd;
      this.key = key;
    }

    @Override
    public void run() {
      CommandState editorState = CommandState.getInstance(editor);
      boolean wasRecording = editorState.isRecording();

      editorState.setCommandState(CurrentCommandState.NEW_COMMAND);
      executeVimAction(editor, cmd.getAction(), context);
      if (editorState.getMode() == CommandState.Mode.INSERT || editorState.getMode() == CommandState.Mode.REPLACE) {
        VimPlugin.getChange().processCommand(editor, cmd);
      }

      // Now the command has been executed let's clean up a few things.

      // By default, the "empty" register is used by all commands, so we want to reset whatever the last register
      // selected by the user was to the empty register - unless we just executed the "select register" command.
      if (cmd.getType() != Command.Type.SELECT_REGISTER) {
        VimPlugin.getRegister().resetRegister();
      }

      // If, at this point, we are not in insert, replace, or visual modes, we need to restore the previous
      // mode we were in. This handles commands in those modes that temporarily allow us to execute normal
      // mode commands. An exception is if this command should leave us in the temporary mode such as
      // "select register"
      if (editorState.getSubMode() == CommandState.SubMode.SINGLE_COMMAND &&
          (!cmd.getFlags().contains(CommandFlags.FLAG_EXPECT_MORE))) {
        editorState.popModes();
      }

      KeyHandler.getInstance().reset(editor);

      if (wasRecording && editorState.isRecording()) {
        VimPlugin.getRegister().recordKeyStroke(key);
      }
    }

    private final Editor editor;
    private final DataContext context;
    private final Command cmd;
    private final KeyStroke key;
  }

  private TypedActionHandler origHandler;

  private static KeyHandler instance;
}
