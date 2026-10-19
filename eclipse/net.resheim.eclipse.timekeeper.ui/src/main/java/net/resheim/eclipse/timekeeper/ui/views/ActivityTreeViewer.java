/*******************************************************************************
 * Copyright (c) 2026 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.views;

import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.FocusCellOwnerDrawHighlighter;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerEditor;
import org.eclipse.jface.viewers.TreeViewerFocusCellManager;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import net.resheim.eclipse.timekeeper.domain.Activity;

/** Select an activity before allowing an explicit click or Enter to edit its cell. */
class ActivityTreeViewer extends TreeViewer {

	private IStructuredSelection selectionBeforeClick = StructuredSelection.EMPTY;

	ActivityTreeViewer(Composite parent, int style) {
		super(parent, style);
		var focus = new TreeViewerFocusCellManager(this, new FocusCellOwnerDrawHighlighter(this)) {
			@Override
			public ViewerCell getFocusCell() {
				ViewerCell cell = super.getFocusCell();
				// Refresh can replace an immutable Activity while retaining its TreeItem.
				// Keyboard editing must use that item's current snapshot, not a cached one.
				return cell == null || cell.getItem().isDisposed() ? null
						: getViewerRowFromItem(cell.getItem()).getCell(cell.getColumnIndex());
			}
		};
		var activation = new ColumnViewerEditorActivationStrategy(this) {
			@Override
			protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event) {
				if (!(((ViewerCell) event.getSource()).getElement() instanceof Activity activity)) return false;
				return switch (event.eventType) {
				case ColumnViewerEditorActivationEvent.MOUSE_CLICK_SELECTION,
						ColumnViewerEditorActivationEvent.MOUSE_DOUBLE_CLICK_SELECTION -> {
					MouseEvent mouse = (MouseEvent) event.sourceEvent;
					yield mouse.button == 1 && (mouse.stateMask & SWT.MODIFIER_MASK) == 0
							&& isSoleSelectedActivity(selectionBeforeClick, activity);
				}
				case ColumnViewerEditorActivationEvent.KEY_PRESSED ->
						(event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
						&& (event.stateMask & SWT.MODIFIER_MASK) == 0
						&& isSoleSelectedActivity(getStructuredSelection(), activity);
				case ColumnViewerEditorActivationEvent.PROGRAMMATIC,
						ColumnViewerEditorActivationEvent.TRAVERSAL ->
						isSoleSelectedActivity(getStructuredSelection(), activity);
				default -> false;
				};
			}
		};
		TreeViewerEditor.create(this, focus, activation,
				ColumnViewerEditor.KEYBOARD_ACTIVATION | ColumnViewerEditor.KEEP_EDITOR_ON_DOUBLE_CLICK);
	}

	@Override
	protected void hookEditingSupport(Control control) {
		// Capture the selection before JFace processes the click; checking it after
		// editor activation has begun can mistake the first click for a second one.
		control.addListener(SWT.MouseDown, event -> selectionBeforeClick = getStructuredSelection());
		super.hookEditingSupport(control);
	}

	private static boolean isSoleSelectedActivity(IStructuredSelection selection, Activity activity) {
		return selection.size() == 1 && selection.getFirstElement() instanceof Activity selected
				&& selected.id().equals(activity.id());
	}
}
