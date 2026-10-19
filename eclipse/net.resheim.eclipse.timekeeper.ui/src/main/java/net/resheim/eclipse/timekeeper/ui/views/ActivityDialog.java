/*******************************************************************************
 * Copyright © 2026 Torkild U. Resheim
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *     Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui.views;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.LabelId;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

/** Creates or edits a service-owned interval, including its calendar dates and labels. */
final class ActivityDialog extends AsyncRecordDialog {
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
			.withResolverStyle(ResolverStyle.STRICT);
	private final TimekeeperService service;
	private final TaskId taskId;
	private final Activity original;
	private final LocalDate date;
	private final ZoneId zone = TimekeeperUiPlugin.getCalendarZone();
	private Text start;
	private Text end;
	private Text summary;
	private CheckboxTableViewer labels;
    private java.util.List<net.resheim.eclipse.timekeeper.domain.Label> labelSnapshot=java.util.List.of();
    private Set<LabelId> deletedLabels=Set.of();
    private final net.resheim.eclipse.timekeeper.db.DatabaseChangeListener backendListener=this::refreshLabels;
    private boolean listening;


	ActivityDialog(Shell shell, TimekeeperService service, Task task, LocalDate date) {
		this(shell, service, task.id(), null, date);
	}
	ActivityDialog(Shell shell, TimekeeperService service, Activity activity, LocalDate date) {
		this(shell, service, activity.taskId(), activity, date);
	}
	private ActivityDialog(Shell shell, TimekeeperService service, TaskId taskId, Activity original, LocalDate date) {
		super(shell,service,original);
		this.service = service;
		this.taskId = taskId;
		this.original = original;
		this.date = date;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}
	@Override protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(original == null ? "New activity" : "Edit activity");
	}
	@Override protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(original == null ? "Record an activity" : "Edit recorded activity");
		setMessage("Enter dates and times in " + zone + ". Leave End empty for an ongoing activity.");
		Composite body = new Composite(area, SWT.NONE);
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		body.setLayout(new GridLayout(2, false));
		new Label(body, SWT.NONE).setText("Task");
        new Label(body,SWT.NONE).setText(taskId==null ? "Unassigned activity" : service.task(taskId).orElseThrow().summary());
		summary = field(body, "Summary", original == null ? "" : original.summary());
		start = field(body, "Start (yyyy-MM-dd HH:mm)", original == null ? FORMAT.format(date.atTime(9, 0))
				: FORMAT.format(original.start().atZone(zone)));
		end = field(body, "End (yyyy-MM-dd HH:mm)", original == null ? FORMAT.format(date.atTime(10, 0))
				: original.end().map(value -> FORMAT.format(value.atZone(zone))).orElse(""));
		labels = CheckboxTableViewer.newCheckList(body, SWT.BORDER | SWT.V_SCROLL);
		GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
		data.heightHint = 120;
		labels.getTable().setLayoutData(data);
		labels.getTable().setData("org.eclipse.swtbot.widget.key", "activity-labels");
		labels.setContentProvider(ArrayContentProvider.getInstance());
		labels.setLabelProvider(new LabelProvider() {
			@Override public String getText(Object element) { var label=(net.resheim.eclipse.timekeeper.domain.Label) element;return label.name()+(deletedLabels.contains(label.id()) ? " (deleted)" : ""); }
		});
		var available = service.labels();
        labelSnapshot=java.util.List.copyOf(available);
		labels.setInput(available);
		if (original != null) labels.setCheckedElements(available.stream()
				.filter(label -> original.labelIds().contains(label.id())).toArray());
        TimekeeperUiPlugin.getDefault().addBackendListener(backendListener);listening=true;
		return area;
	}
    private void refreshLabels() {
        if(labels==null || labels.getControl().isDisposed()) return;
        var display=labels.getControl().getDisplay();
        if(org.eclipse.swt.widgets.Display.getCurrent()!=display) {display.asyncExec(this::refreshLabels);return;}
        if(!TimekeeperUiPlugin.getDefault().isBackendReady()) return;
        var checked=Arrays.stream(labels.getCheckedElements()).map(value->(net.resheim.eclipse.timekeeper.domain.Label)value).toList();
        var selected=checked.stream().map(net.resheim.eclipse.timekeeper.domain.Label::id).collect(Collectors.toSet());
        var latest=service.labels();if(latest.equals(labelSnapshot)) return;labelSnapshot=java.util.List.copyOf(latest);
        var available=new java.util.ArrayList<>(latest);
        var missing=new java.util.HashSet<LabelId>();
        // Keep a selected, deleted reference visible until the user reviews it.
        // Silently dropping it would change the draft's intended label set.
        for(var value:checked) if(available.stream().noneMatch(label->label.id().equals(value.id()))) {available.add(value);missing.add(value.id());}
        deletedLabels=Set.copyOf(missing);
        labels.setInput(available);labels.setCheckedElements(available.stream().filter(value->selected.contains(value.id())).toArray());
    }
    @Override public boolean close() {
        boolean closed=super.close();
        var plugin=TimekeeperUiPlugin.getDefault();
        if(closed && listening && plugin!=null) {plugin.removeBackendListener(backendListener);listening=false;}
        return closed;
    }
	private Text field(Composite parent, String caption, String value) {
		new Label(parent, SWT.NONE).setText(caption);
		Text text = new Text(parent, SWT.BORDER);
		GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
		data.widthHint = 320;
		text.setLayoutData(data);
		text.setText(value);
		text.addModifyListener(event -> setErrorMessage(null));
		return text;
	}
	@Override protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Save", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
	}
	@Override protected void okPressed() {
		try {
			var from = endpoint(start.getText(), original == null ? Optional.empty() : Optional.of(original.start()));
			Optional<java.time.Instant> until = end.getText().isBlank() ? Optional.empty()
					: Optional.of(endpoint(end.getText(), original == null ? Optional.empty() : original.end()));
			Activity.validateStart(from, java.time.Instant.now());
			Set<LabelId> selected = Arrays.stream(labels.getCheckedElements())
					.map(value -> ((net.resheim.eclipse.timekeeper.domain.Label) value).id()).collect(Collectors.toSet());
            String entered=summary.getText();
            save("Save Timekeeper activity",()-> original==null ? service.createActivity(new CreateActivity(taskId,OwnerId.LOCAL,from,until,entered,selected))
                    : service.updateActivity(new UpdateActivity(original.id(),original.version(),from,until,entered,selected)),value -> {});
		} catch (RuntimeException failure) {
			setErrorMessage(failureMessage(failure));
		}
	}

	private java.time.Instant endpoint(String text, Optional<java.time.Instant> stored) {
		String value = text.trim();
		// The editor displays minutes; unchanged fields retain their original precision and offset.
		return stored.filter(instant -> FORMAT.format(instant.atZone(zone)).equals(value))
				.orElseGet(() -> LocalDateTime.parse(value, FORMAT).atZone(zone).toInstant());
	}
	static String failureMessage(RuntimeException failure) {
		if (failure instanceof java.time.format.DateTimeParseException) return "Use a valid date and time in yyyy-MM-dd HH:mm format.";
		if (failure instanceof net.resheim.eclipse.timekeeper.service.ServiceException problem
				&& problem.code() == net.resheim.eclipse.timekeeper.domain.FailureCode.CONFLICT) {
			if (problem.field().equals("activity")) return "This activity has changed. Close and reopen the editor before saving.";
			if (problem.field().equals("ownerId")) return "Stop the current activity before starting another.";
		}
		return failure.getMessage();
	}

}
