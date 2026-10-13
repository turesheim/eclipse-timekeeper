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

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import net.resheim.eclipse.timekeeper.domain.ProjectId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** Task editing uses native identities; an external provider is optional. */
final class TaskDialog extends AsyncRecordDialog {
	private record ProjectChoice(Optional<ProjectId> id, String name) { }
	private record ParentChoice(Optional<TaskId> id, String name) { }
	private final TimekeeperService service;
	private final Task original;
	private final Optional<ProjectId> initialProject;
	private final Optional<TaskId> initialParent;
	private final List<Task> tasks;
	private Text summary;
	private Text url;
	private ComboViewer project;
	private ComboViewer parentTask;
	private Task saved;

	TaskDialog(Shell shell, TimekeeperService service, Task original,
			Optional<ProjectId> projectId, Optional<TaskId> parentId) {
		super(shell,service,original);
		this.service = service;
		this.original = original;
		this.initialProject = original == null ? projectId : original.projectId();
		this.initialParent = original == null ? parentId : original.parentTaskId();
		tasks = service.tasks();
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}
	@Override protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(original == null ? "New task" : "Edit task");
	}
	@Override protected Control createDialogArea(Composite composite) {
		Composite area = (Composite) super.createDialogArea(composite);
		setTitle(original == null ? "Create a Timekeeper task" : "Edit Timekeeper task");
		setMessage("Tasks can be used on their own, grouped in a project, or nested under another task.");
		Composite body = new Composite(area, SWT.NONE);
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		body.setLayout(new GridLayout(2, false));
		summary = field(body, "Summary", original == null ? "" : original.summary());
		url = field(body, "URL (optional)", original == null ? "" : original.url().orElse(""));
		project = choices(body, "Project");
		List<ProjectChoice> projects = new java.util.ArrayList<>();
		projects.add(new ProjectChoice(Optional.empty(), "No project"));
		service.projects().stream().sorted(Comparator.comparing(value -> value.name()))
				.forEach(value -> projects.add(new ProjectChoice(Optional.of(value.id()), value.name())));
		project.setLabelProvider(new LabelProvider() {
			@Override public String getText(Object value) { return ((ProjectChoice) value).name(); }
		});
		project.setInput(projects);
		project.setSelection(new StructuredSelection(projects.stream().filter(value -> value.id().equals(initialProject))
				.findFirst().orElse(projects.get(0))));
		parentTask = choices(body, "Parent task");
		parentTask.setLabelProvider(new LabelProvider() {
			@Override public String getText(Object value) { return ((ParentChoice) value).name(); }
		});
		updateParents(initialParent);
		project.addSelectionChangedListener(event -> {
			updateParents(Optional.empty());
			setErrorMessage(null);
		});
		new Label(body, SWT.NONE).setText("External links");
		Text references = new Text(body, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP);
		GridData links = new GridData(SWT.FILL, SWT.CENTER, true, false);
		links.widthHint = 360;
		references.setLayoutData(links);
		references.setText(original == null || original.externalReferences().isEmpty() ? "No external links"
				: original.externalReferences().stream().map(reference -> reference.providerId() + ": "
						+ reference.externalId() + " (" + reference.repositoryId() + ")")
						.sorted().collect(java.util.stream.Collectors.joining("\n")));
		return area;
	}
	private Text field(Composite body, String caption, String initial) {
		new Label(body, SWT.NONE).setText(caption);
		Text value = new Text(body, SWT.BORDER);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = 360;
		value.setLayoutData(data);
		value.setText(initial);
		value.addModifyListener(event -> setErrorMessage(null));
		return value;
	}
	private ComboViewer choices(Composite body, String caption) {
		new Label(body, SWT.NONE).setText(caption);
		ComboViewer viewer = new ComboViewer(body, SWT.READ_ONLY);
		viewer.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		viewer.getCombo().setData("org.eclipse.swtbot.widget.key", caption.equals("Project") ? "task-project" : "task-parent");
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		return viewer;
	}
	private Optional<ProjectId> projectId() {
		return ((ProjectChoice) project.getStructuredSelection().getFirstElement()).id();
	}
	private void updateParents(Optional<TaskId> selected) {
		Set<TaskId> excluded = new HashSet<>();
		if (original != null) {
			excluded.add(original.id());
			// Excluding descendants prevents a hierarchy cycle in the picker.
			boolean changed;
			do {
				changed = false;
				for (Task task : tasks) if (task.parentTaskId().filter(excluded::contains).isPresent()) changed |= excluded.add(task.id());
			} while (changed);
		}
		List<ParentChoice> parents = new java.util.ArrayList<>();
		parents.add(new ParentChoice(Optional.empty(), "Top-level task"));
		tasks.stream().filter(task -> projectId().isPresent() && task.projectId().equals(projectId()) && !excluded.contains(task.id()))
				.sorted(Comparator.comparing(Task::summary))
				.forEach(task -> parents.add(new ParentChoice(Optional.of(task.id()), path(task))));
		parentTask.setInput(parents);
		parentTask.setSelection(new StructuredSelection(parents.stream().filter(value -> value.id().equals(selected))
				.findFirst().orElse(parents.get(0))));
	}
	private String path(Task task) {
		String result = task.summary();
		Set<TaskId> visited = new HashSet<>();
		while (task.parentTaskId().isPresent() && visited.add(task.id())) {
			TaskId id = task.parentTaskId().orElseThrow();
			task = tasks.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
			if (task == null) break;
			result = task.summary() + " / " + result;
		}
		return result;
	}
    @Override protected void okPressed() {
        Optional<TaskId> parent=((ParentChoice)parentTask.getStructuredSelection().getFirstElement()).id();
        Optional<ProjectId> selectedProject=projectId();String entered=summary.getText();
        Optional<String> link=Optional.of(url.getText().trim()).filter(value -> !value.isEmpty());
        save("Save Timekeeper task",()-> original==null ? service.createTask(new CreateTask(selectedProject,parent,entered,link))
                : service.updateTask(new UpdateTask(original.id(),original.version(),selectedProject,parent,entered,link)),value -> saved=value);
    }
	Task savedTask() { return saved; }
}
