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
import net.resheim.eclipse.timekeeper.domain.*;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTaskDetails;
import net.resheim.eclipse.timekeeper.domain.TaskId;
import net.resheim.eclipse.timekeeper.service.Commands.CreateTask;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateTask;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;

/** Task editing uses native identities; an external provider is optional. */
final class TaskDialog extends AsyncRecordDialog {
	private record ProjectChoice(Optional<ProjectId> id, String name) { }
	private record ParentChoice(Optional<TaskId> id, String name) { }
    private record ListChoice(Optional<java.util.UUID> id,String name) { }
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
    private ComboViewer home,status,format,priority;
    private Text notes,scheduled,start,startZone,due,dueZone;
    private org.eclipse.core.runtime.jobs.Job listLoad;
    private final boolean remote=TimekeeperUiPlugin.getDefault().isRemote();
    private static String text(String en,String nb){return java.util.Set.of("nb","no","nn").contains(java.util.Locale.getDefault().getLanguage())?nb:en;}


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
		shell.setText(original == null ? text("New task","Ny oppgave") : text("Edit task","Rediger oppgave"));
	}
	@Override protected Control createDialogArea(Composite composite) {
		Composite area = (Composite) super.createDialogArea(composite);
		setTitle(original == null ? text("Create a Dagsverk task","Opprett en Dagsverk-oppgave") : text("Edit Dagsverk task","Rediger Dagsverk-oppgave"));
		setMessage(text("Tasks can be used on their own, grouped in a project, or nested under another task.","Oppgaver kan brukes alene, samles i et prosjekt eller legges under en annen oppgave."));
		Composite body = new Composite(area, SWT.NONE);
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		body.setLayout(new GridLayout(2, false));
		summary = field(body, text("Summary","Tittel"), original == null ? "" : original.summary());
		url = field(body, text("URL (optional)","Nettadresse (valgfri)"), original == null ? "" : original.url().orElse(""));
		project = choices(body, text("Project","Prosjekt"));
		List<ProjectChoice> projects = new java.util.ArrayList<>();
		projects.add(new ProjectChoice(Optional.empty(), text("No project","Uten prosjekt")));
		service.projects().stream().sorted(Comparator.comparing(value -> value.name()))
				.forEach(value -> projects.add(new ProjectChoice(Optional.of(value.id()), value.name())));
		project.setLabelProvider(new LabelProvider() {
			@Override public String getText(Object value) { return ((ProjectChoice) value).name(); }
		});
		project.setInput(projects);
		project.setSelection(new StructuredSelection(projects.stream().filter(value -> value.id().equals(initialProject))
				.findFirst().orElse(projects.get(0))));
		parentTask = choices(body, text("Parent task","Overordnet oppgave"));
		parentTask.setLabelProvider(new LabelProvider() {
			@Override public String getText(Object value) { return ((ParentChoice) value).name(); }
		});
		updateParents(initialParent);
		project.addSelectionChangedListener(event -> {
			updateParents(Optional.empty());
			setErrorMessage(null);
		});
        if(remote)providerFields(body);
        new Label(body, SWT.NONE).setText(text("External links","Eksterne lenker"));
		Text references = new Text(body, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP);
		GridData links = new GridData(SWT.FILL, SWT.CENTER, true, false);
		links.widthHint = 360;
		references.setLayoutData(links);
		references.setText(original == null || original.externalReferences().isEmpty() ? text("No external links","Ingen eksterne lenker")
				: original.externalReferences().stream().map(reference -> reference.providerId() + ": "
						+ reference.externalId() + " (" + reference.repositoryId() + ")")
						.sorted().collect(java.util.stream.Collectors.joining("\n")));
		return area;
	}
    private void providerFields(Composite body){
        home=choices(body,text("Synchronized list","Synkronisert liste"));home.getCombo().setData("org.eclipse.swtbot.widget.key","task-sync-list");
        home.setLabelProvider(new LabelProvider(){@Override public String getText(Object value){return ((ListChoice)value).name();}});
        var choices=new java.util.ArrayList<ListChoice>();choices.add(new ListChoice(Optional.empty(),text("Dagsverk only","Bare i Dagsverk")));
        var selected=original==null?initialParent.flatMap(id->tasks.stream().filter(task->task.id().equals(id)).findFirst()).flatMap(Task::syncListId):original.syncListId();
        selected.ifPresent(id->choices.add(new ListChoice(Optional.of(id),text("Current list (retained)","Nåværende liste (beholdes)"))));
        home.setInput(choices);home.setSelection(new StructuredSelection(choices.get(choices.size()-1)));
        listLoad=TimekeeperUiPlugin.getDefault().runOperation(text("Load synchronized lists","Hent synkroniserte lister"),service::synchronizedTaskLists,lists->{
            if(home.getControl().isDisposed())return;var current=((ListChoice)home.getStructuredSelection().getFirstElement()).id();
            var available=new java.util.ArrayList<ListChoice>();available.add(choices.get(0));lists.forEach(list->available.add(new ListChoice(Optional.of(list.id()),list.name())));
            if(current.isPresent()&&available.stream().noneMatch(choice->choice.id().equals(current)))available.add(new ListChoice(current,text("Current list (retained)","Nåværende liste (beholdes)")));
            home.setInput(available);home.setSelection(new StructuredSelection(available.stream().filter(choice->choice.id().equals(current)).findFirst().orElse(available.get(0))));
        },failure->{if(getShell()!=null&&!getShell().isDisposed())setErrorMessage(RecordDialogErrors.editorMessage(failure));});
        status=choices(body,text("Status","Status"));status.getCombo().setData("org.eclipse.swtbot.widget.key","task-status");status.setLabelProvider(new LabelProvider(){@Override public String getText(Object value){return switch((TaskStatus)value){case NOT_STARTED->text("Not started","Ikke startet");case IN_PROGRESS->text("In progress","Pågår");case COMPLETED->text("Completed","Fullført");case WAITING->text("Waiting","Venter");case DEFERRED->text("Deferred","Utsatt");};}});status.setInput(TaskStatus.values());status.setSelection(new StructuredSelection(original==null?TaskStatus.NOT_STARTED:original.planning().status()));
        scheduled=field(body,text("Scheduled day (YYYY-MM-DD)","Planlagt dag (ÅÅÅÅ-MM-DD)"),original==null?"":original.planning().scheduledDate().map(Object::toString).orElse(""));
        format=choices(body,text("Notes format","Notatformat"));format.setInput(TaskBodyFormat.values());format.setSelection(new StructuredSelection(original==null?TaskBodyFormat.TEXT:original.body().format()));
        new Label(body,SWT.NONE).setText(text("Notes","Notater"));notes=new Text(body,SWT.BORDER|SWT.MULTI|SWT.WRAP|SWT.V_SCROLL);notes.setText(original==null?"":original.body().content());var size=new GridData(SWT.FILL,SWT.FILL,true,true);size.heightHint=100;notes.setLayoutData(size);
        priority=choices(body,text("Priority","Prioritet"));priority.setInput(TaskPriority.values());priority.setSelection(new StructuredSelection(original==null?TaskPriority.UNSPECIFIED:original.importance().priority()));
        start=field(body,text("Start (date or local date/time)","Start (dato eller lokal dato/tid)"),momentText(original==null?Optional.empty():original.timing().start()));
        startZone=field(body,text("Start time zone","Tidssone for start"),original==null?"":original.timing().start().flatMap(TaskMoment::timeZone).orElse(""));
        due=field(body,text("Deadline (date or local date/time)","Frist (dato eller lokal dato/tid)"),momentText(original==null?Optional.empty():original.timing().due()));
        dueZone=field(body,text("Deadline time zone","Tidssone for frist"),original==null?"":original.timing().due().flatMap(TaskMoment::timeZone).orElse(""));
    }
    private static String momentText(Optional<TaskMoment> moment){return moment.map(value->value.date().toString()+value.time().map(time->"T"+time).orElse("")).orElse("");}
    private Optional<TaskMoment> moment(Text value,Text zone){
        String entered=value.getText().trim();if(entered.isEmpty())return Optional.empty();
        if(!entered.contains("T"))return Optional.of(TaskMoment.dateOnly(java.time.LocalDate.parse(entered)));
        var local=java.time.LocalDateTime.parse(entered);return Optional.of(TaskMoment.zoned(local.toLocalDate(),local.toLocalTime(),zone.getText().trim()));
    }
    @Override public boolean close(){if(listLoad!=null)listLoad.cancel();return super.close();}
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
		if(caption.equals(text("Project","Prosjekt")))viewer.getCombo().setData("org.eclipse.swtbot.widget.key","task-project");
        else if(caption.equals(text("Parent task","Overordnet oppgave")))viewer.getCombo().setData("org.eclipse.swtbot.widget.key","task-parent");
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
		parents.add(new ParentChoice(Optional.empty(), text("Top-level task","Oppgave på øverste nivå")));
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
        if(!remote){save(text("Save Dagsverk task","Lagre Dagsverk-oppgave"),()->original==null?service.createTask(new CreateTask(selectedProject,parent,entered,link)):service.updateTask(new UpdateTask(original.id(),original.version(),selectedProject,parent,entered,link)),value->saved=value);return;}
        try{
            var selectedHome=((ListChoice)home.getStructuredSelection().getFirstElement()).id();
            var selectedStatus=(TaskStatus)status.getStructuredSelection().getFirstElement();
            var planning=new TaskPlanning(selectedStatus,Optional.of(scheduled.getText().trim()).filter(value->!value.isEmpty()).map(java.time.LocalDate::parse),selectedStatus==TaskStatus.COMPLETED&&original!=null?original.planning().completedAt():Optional.empty());
            var body=new TaskBody((TaskBodyFormat)format.getStructuredSelection().getFirstElement(),notes.getText());
            var timing=new TaskTiming(moment(start,startZone),moment(due,dueZone));
            var importance=new TaskImportance((TaskPriority)priority.getStructuredSelection().getFirstElement(),original!=null&&original.importance().flagged(),original!=null&&original.importance().urgent());
            save(text("Save Dagsverk task","Lagre Dagsverk-oppgave"),()->original==null?
                service.createTask(new CreateTask(selectedProject,parent,entered,link,Set.of(),planning,TaskChecklist.empty(),timing,importance,body,0,Set.of(),TaskRecurrence.empty(),List.of(),TaskMetadata.empty(),selectedHome)):
                service.updateTaskDetails(new UpdateTaskDetails(new UpdateTask(original.id(),original.version(),selectedProject,parent,entered,link,Optional.of(selectedHome)),planning,body,timing,importance,original.siblingOrder(),original.metadata())),value->saved=value);
        }catch(RuntimeException invalid){setErrorMessage(RecordDialogErrors.editorMessage(invalid));}
    }
	Task savedTask() { return saved; }
}
