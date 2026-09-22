package net.resheim.eclipse.timekeeper.ui.tasks;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.ExternalTaskReference;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.service.Commands.LinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UnlinkExternalReference;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** Client-only management UI for native Timekeeper tasks. */
public final class StandaloneTaskManagerDialog extends TitleAreaDialog {
	private final TimekeeperService service;
	private TableViewer viewer;
	private Button editButton;
	private Button deleteButton;
	private Button openButton;
	private Button linkButton;
	private Button unlinkButton;
	private Button activityButton;
	private Optional<Activity> activeActivity = Optional.empty();

	public StandaloneTaskManagerDialog(Shell parentShell, TimekeeperService service) {
		super(parentShell);
		this.service = service;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Timekeeper Tasks");
	}

	@Override
	protected Point getInitialSize() {
		return new Point(780, 430);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		setTitle("Timekeeper tasks");
		setMessage("Create native tasks, track them, or connect them to an external provider later.");
		Composite area = (Composite) super.createDialogArea(parent);
		Composite content = new Composite(area, SWT.NONE);
		content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		content.setLayout(new GridLayout(2, false));

		viewer = new TableViewer(content, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
		Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);
		table.setData("org.eclipse.swtbot.widget.key", "standalone-task-table");
		table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.setComparator(new org.eclipse.jface.viewers.ViewerComparator() {
			@Override public int compare(org.eclipse.jface.viewers.Viewer source, Object left, Object right) {
				return String.CASE_INSENSITIVE_ORDER.compare(((Task) left).summary(), ((Task) right).summary());
			}
		});
		column("Summary", 280, task -> task.summary());
		column("URL", 260, task -> task.url().orElse(""));
		column("External links", 160, this::externalLinks);
		viewer.addSelectionChangedListener(event -> updateButtons());
		viewer.addDoubleClickListener(event -> edit());

		Composite buttons = new Composite(content, SWT.NONE);
		buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
		buttons.setLayout(new GridLayout(1, true));
		button(buttons, "New...", this::createTask);
		editButton = button(buttons, "Edit...", this::edit);
		deleteButton = button(buttons, "Delete", this::delete);
		openButton = button(buttons, "Open URL", this::openUrl);
		linkButton = button(buttons, "Link...", this::link);
		unlinkButton = button(buttons, "Unlink...", this::unlink);
		activityButton = button(buttons, "Start Activity", this::toggleActivity);
		refresh(null);
		return area;
	}

	private void column(String title, int width, java.util.function.Function<Task, String> text) {
		TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setText(title);
		column.getColumn().setWidth(width);
		column.setLabelProvider(new ColumnLabelProvider() {
			@Override public String getText(Object element) { return text.apply((Task) element); }
		});
	}

	private Button button(Composite parent, String text, Runnable action) {
		Button button = new Button(parent, SWT.PUSH);
		button.setText(text);
		button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		button.addListener(SWT.Selection, event -> action.run());
		return button;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
	}

	@Override
	protected void buttonPressed(int buttonId) {
		if (buttonId == IDialogConstants.CLOSE_ID) close();
		else super.buttonPressed(buttonId);
	}

	private void createTask() {
		TaskEditorDialog dialog = new TaskEditorDialog(getShell(), null);
		if (dialog.open() != Window.OK) return;
		run("create task", () -> {
			Task created = service.createTask(new CreateTask(Optional.empty(), dialog.summary(), dialog.url()));
			refresh(created.id());
		});
	}

	private void edit() {
		selected().ifPresent(task -> {
			TaskEditorDialog dialog = new TaskEditorDialog(getShell(), task);
			if (dialog.open() != Window.OK) return;
			run("update task", () -> {
				Task updated = service.updateTask(new UpdateTask(task.id(), task.version(), task.projectId(),
						dialog.summary(), dialog.url()));
				refresh(updated.id());
			});
		});
	}

	private void delete() {
		selected().ifPresent(task -> {
			if (!MessageDialog.openQuestion(getShell(), "Delete Timekeeper task",
					"Delete \"" + task.summary() + "\"? Tasks with recorded activities cannot be deleted.")) return;
			run("delete task", () -> {
				service.deleteTask(new DeleteTask(task.id(), task.version()));
				refresh(null);
			});
		});
	}

	private void link() {
		selected().ifPresent(task -> {
			ExternalReferenceDialog dialog = new ExternalReferenceDialog(getShell());
			if (dialog.open() != Window.OK) return;
			run("link external task", () -> {
				Task updated = service.linkExternalReference(new LinkExternalReference(task.id(), task.version(),
						dialog.reference()));
				refresh(updated.id());
			});
		});
	}

	private void unlink() {
		selected().ifPresent(task -> {
			ExternalTaskReference reference = chooseReference(task.externalReferences());
			if (reference == null) return;
			run("unlink external task", () -> {
				Task updated = service.unlinkExternalReference(new UnlinkExternalReference(task.id(), task.version(),
						reference.key()));
				refresh(updated.id());
			});
		});
	}

	private ExternalTaskReference chooseReference(Set<ExternalTaskReference> references) {
		if (references.size() == 1) return references.iterator().next();
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(getShell(), new LabelProvider() {
			@Override public String getText(Object element) {
				ExternalTaskReference reference = (ExternalTaskReference) element;
				return reference.providerId() + ": " + reference.externalId();
			}
		});
		dialog.setTitle("Unlink External Task");
		dialog.setMessage("Select the external reference to remove:");
		dialog.setElements(references.toArray());
		return dialog.open() == Window.OK ? (ExternalTaskReference) dialog.getFirstResult() : null;
	}

	private void toggleActivity() {
		selected().ifPresent(task -> run(activeActivity.filter(activity -> activity.taskId().equals(task.id())).isPresent()
				? "stop activity" : "start activity", () -> {
			if (activeActivity.filter(activity -> activity.taskId().equals(task.id())).isPresent()) {
				Activity activity = activeActivity.orElseThrow();
				service.stopActivity(new StopActivity(activity.id(), activity.version()));
			} else {
				service.startActivity(new StartActivity(task.id(), OwnerId.LOCAL, task.summary(), Set.of()));
			}
			refresh(task.id());
		}));
	}

	private void openUrl() {
		selected().flatMap(this::url).ifPresent(value -> run("open URL", () -> {
			int style = IWorkbenchBrowserSupport.AS_EDITOR | IWorkbenchBrowserSupport.LOCATION_BAR
					| IWorkbenchBrowserSupport.NAVIGATION_BAR;
			PlatformUI.getWorkbench().getBrowserSupport()
					.createBrowser(style, "timekeeper.task", "Timekeeper Task", "Timekeeper task link")
					.openURL(URI.create(value).toURL());
		}));
	}

	private Optional<String> url(Task task) {
		return task.url().or(() -> task.externalReferences().stream()
				.map(ExternalTaskReference::externalUrl).flatMap(Optional::stream).findFirst());
	}

	private String externalLinks(Task task) {
		if (task.externalReferences().isEmpty()) return "Standalone";
		return task.externalReferences().stream()
				.sorted(Comparator.comparing(ExternalTaskReference::providerId)
						.thenComparing(ExternalTaskReference::externalId))
				.map(reference -> reference.providerId() + ":" + reference.externalId())
				.reduce((left, right) -> left + ", " + right).orElse("");
	}

	private void refresh(TaskId selection) {
		List<Task> tasks = service.tasks();
		activeActivity = service.activeActivity(OwnerId.LOCAL);
		viewer.setInput(tasks);
		if (selection != null) tasks.stream().filter(task -> task.id().equals(selection)).findFirst()
				.ifPresent(task -> viewer.setSelection(new StructuredSelection(task), true));
		updateButtons();
	}

	private Optional<Task> selected() {
		Object selected = viewer.getStructuredSelection().getFirstElement();
		return selected instanceof Task task ? Optional.of(task) : Optional.empty();
	}

	private void updateButtons() {
		Task task = selected().orElse(null);
		boolean selected = task != null;
		editButton.setEnabled(selected);
		deleteButton.setEnabled(selected);
		linkButton.setEnabled(selected);
		unlinkButton.setEnabled(selected && !task.externalReferences().isEmpty());
		openButton.setEnabled(selected && url(task).isPresent());
		boolean selectedIsActive = selected && activeActivity
				.filter(activity -> activity.taskId().equals(task.id())).isPresent();
		activityButton.setText(selectedIsActive ? "Stop Activity" : "Start Activity");
		activityButton.setEnabled(selected && (activeActivity.isEmpty() || selectedIsActive));
	}

	private void run(String operation, ThrowingAction action) {
		try {
			action.run();
			setErrorMessage(null);
		} catch (Exception e) {
			MessageDialog.openError(getShell(), "Could not " + operation,
					e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	@FunctionalInterface
	private interface ThrowingAction {
		void run() throws Exception;
	}
}
