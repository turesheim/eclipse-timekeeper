/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.views;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.service.Commands.CreateProject;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateProject;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** Edits a versioned project snapshot through the client API. */
final class ProjectDialog extends AsyncRecordDialog {
	private final TimekeeperService service;
	private final Project original;
	private Text name;
	private Project saved;

	ProjectDialog(Shell shell, TimekeeperService service, Project original) {
		super(shell,service,original);
		this.service = service;
		this.original = original;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}
	@Override protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(original == null ? "New project" : "Edit project");
	}
	@Override protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(original == null ? "Create a Timekeeper project" : "Edit Timekeeper project");
		setMessage("A project groups tasks and their recorded time.");
		Composite body = new Composite(area, SWT.NONE);
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		body.setLayout(new GridLayout(2, false));
		new Label(body, SWT.NONE).setText("Name");
		name = new Text(body, SWT.BORDER);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = 320;
		name.setLayoutData(data);
		name.setText(original == null ? "" : original.name());
		name.addModifyListener(event -> setErrorMessage(null));
		return area;
	}
    @Override protected void okPressed() {
        String entered=name.getText();
        save("Save Timekeeper project",()-> original==null ? service.createProject(new CreateProject(entered))
                : service.updateProject(new UpdateProject(original.id(),original.version(),entered)),value -> saved=value);
    }
	Project savedProject() { return saved; }
}
