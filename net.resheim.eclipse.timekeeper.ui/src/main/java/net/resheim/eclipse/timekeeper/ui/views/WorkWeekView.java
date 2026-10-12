/*******************************************************************************
 * Copyright (c) 2014-2020 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/

package net.resheim.eclipse.timekeeper.ui.views;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeColumnViewerLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.part.ViewPart;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.Project;
import net.resheim.eclipse.timekeeper.domain.Task;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.StopActivity;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteActivity;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteProject;
import net.resheim.eclipse.timekeeper.service.Commands.DeleteTask;
import net.resheim.eclipse.timekeeper.ui.ActivityLabelPainter;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;
import net.resheim.eclipse.timekeeper.ui.TimekeeperUiPlugin;

@SuppressWarnings("restriction")
public class WorkWeekView extends ViewPart {

	public static final String VIEW_ID = "net.resheim.eclipse.timekeeper.ui.views.workWeek";

	private static final int TIME_COLUMN_WIDTH = 50;

	/** Update the status field every second */
	private static final int UPDATE_INTERVAL = 1_000;

	private final class ViewerComparatorExtension extends ViewerComparator {

		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {

			if (e1 instanceof WeeklySummary) {
				return 1;
			}
			if (e1 instanceof Activity && e2 instanceof Activity) {
				return ((Activity) e1).start().compareTo(((Activity) e2).start());
			}
			return super.compare(viewer, e1, e2);
		}

	}

	private void installStatusUpdater() {
		// use Flowable here?
		final Display display = PlatformUI.getWorkbench().getDisplay();
		Runnable handler = new Runnable() {
			public void run() {
				if (!display.isDisposed() && !PlatformUI.getWorkbench().isClosing() && !statusLabel.isDisposed()) {
					updateStatus();
					display.timerExec(UPDATE_INTERVAL, this);
				}
			}
		};
		display.timerExec(UPDATE_INTERVAL, handler);
	}

	/**
	 * Returns the number of milliseconds the active task has been active
	 *
	 * @return the active milliseconds or "0"
	 */
	public long getActiveTime() {
		if (!TimekeeperUiPlugin.getDefault().snapshotsAvailable()) return 0L;
		return TimekeeperUiPlugin.getDefault().getTimekeeperService()
				.activeActivity(OwnerId.LOCAL)
				.map(activity -> Math.max(0, Duration.between(activity.start(), Instant.now()).toMillis())).orElse(0L);
	}

	private void updateStatus() {
		boolean ready = TimekeeperUiPlugin.getDefault().isBackendReady();
		newProjectAction.setEnabled(ready);
		newTaskAction.setEnabled(ready);
		if (!ready || !TimekeeperUiPlugin.getDefault().backendStatus().isBlank()) {
			statusLabel.setText(TimekeeperUiPlugin.getDefault().backendStatus());
			return;
		}
		var active = TimekeeperUiPlugin.getDefault().getTimekeeperService()
				.activeActivity(OwnerId.LOCAL);
		if (active.isEmpty()) {
			refreshActiveTask();
			statusLabel.setText("");
			return;
		}
		Activity activity = active.orElseThrow();
		Instant idleSince = TimekeeperUiPlugin.getDefault().getIdleSince();
		if (TimekeeperUiPlugin.getDefault().isIdle() && idleSince != null) {
			statusLabel.setText("Idle since " + timeFormat.withZone(TimekeeperUiPlugin.getCalendarZone()).format(idleSince));
		} else {
			long elapsed = Math.max(0, Duration.between(activity.start(), Instant.now()).toMillis());
			statusLabel.setText(MessageFormat.format("Active since {0}, {1} elapsed", timeFormat.withZone(TimekeeperUiPlugin.getCalendarZone()).format(activity.start()),
					DurationFormatUtils.formatDurationWords(elapsed, true, true)));
		}
		refreshActiveTask();
	}

	private void refreshActiveTask() {
		if (!viewer.isCellEditorActive()) {
			contentProvider.filter();
			viewer.refresh();
		}
	}

	private class ContentProvider extends WeekViewContentProvider {

		@Override
		public void inputChanged(Viewer v, Object oldInput, Object newInput) {
			super.inputChanged(v, oldInput, newInput);
			if (v.getControl().isDisposed() || dateTimeLabel.isDisposed()) {
				return;
			}
			updateWeekLabel();
			filter();
			updateColumHeaders();
			v.refresh();
		}

		private void updateColumHeaders() {
			TreeColumn[] columns = viewer.getTree().getColumns();
			String[] headings = TimekeeperUiPlugin.getDefault().getHeadings(getFirstDayOfWeek());
			for (int i = 1; i < columns.length; i++) {
				LocalDate date = getFirstDayOfWeek().plusDays((long) i - 1);
				columns[i].setText(headings[i - 1]);
				columns[i].setToolTipText(getFormattedPeriod(contentProvider.seconds(WeekViewContentProvider.WEEKLY_SUMMARY, date)));
			}
		}

		private void updateWeekLabel() {
			StringBuilder sb = new StringBuilder();
			sb.append("Showing week ");
			sb.append(super.getFirstDayOfWeek().format(weekFormat));
			sb.append(" starting on");
			dateTimeLabel.setText(sb.toString());
			dateChooser.setDate(getFirstDayOfWeek().getYear(), getFirstDayOfWeek().getMonthValue() - 1,
					getFirstDayOfWeek().getDayOfMonth());
		}

	}

	private static final DateTimeFormatter weekFormat = DateTimeFormatter.ofPattern("w");

	private static final DateTimeFormatter timeFormat = DateTimeFormatter
			.ofPattern("EEEE HH:mm:ss", Locale.ENGLISH).withZone(TimekeeperUiPlugin.getCalendarZone());

	private Action previousWeekAction;

	private Action currentWeekAction;

	private Action newActivityAction;
	private Action newProjectAction;
	private Action newTaskAction;
	private Action showAllTasksAction;
	private Action collapseAllAction;
	private Action expandAllAction;

	private Action nextWeekAction;

	private Action doubleClickAction;

	private Action deleteAction;


	private TreeViewer viewer;

	private Label dateTimeLabel;

	private DateTime dateChooser;

	private Action deactivateAction;

	private Action activateAction;

	private WeekViewContentProvider contentProvider;

	private Label statusLabel;

	private ActivityLabelPainter activityLabelPainter;

	/**
	 * The constructor.
	 */
	public WorkWeekView() {
	}

	private LocalDate calculateFirstDayOfWeek(LocalDate date) {
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		long day = date.get(weekFields.dayOfWeek());
		LocalDate firstDayOfWeek = date.minusDays(day - 1);
		return firstDayOfWeek;
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	@Override
	public void createPartControl(Composite parent) {
		Composite main = new Composite(parent, SWT.NONE);
		main.setBackgroundMode(SWT.INHERIT_FORCE);
		GridLayout layout2 = GridLayoutFactory.fillDefaults().numColumns(3).equalWidth(false).create();
		main.setLayout(layout2);

		// text before the date chooser
		dateTimeLabel = new Label(main, SWT.NONE);
		GridData gdLabel = new GridData();
		gdLabel.verticalIndent = 3;
		gdLabel.verticalAlignment = SWT.BEGINNING;
		dateTimeLabel.setLayoutData(gdLabel);

		dateChooser = new DateTime(main, SWT.DROP_DOWN | SWT.DATE | SWT.LONG);
		// the DateTime background color is misbehaving, but this is not too bad
		// although it does not stand out but rather have the same background
		// color as the labels.
		dateChooser.setBackgroundMode(SWT.INHERIT_DEFAULT);
		dateChooser.setFont(dateTimeLabel.getFont());

		GridData gdChooser = new GridData();
		gdChooser.verticalAlignment = SWT.BEGINNING;
		dateChooser.setLayoutData(gdChooser);
		dateChooser.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent event) {
				// Determine the first date of the week
				LocalDate date = LocalDate.of(dateChooser.getYear(), dateChooser.getMonth() + 1, dateChooser.getDay());
				contentProvider.setFirstDayOfWeek(calculateFirstDayOfWeek(date));
				viewer.setInput(this);
			}
		});

		// text after the date chooser
		statusLabel = new Label(main, SWT.NONE);
		GridData gdStatusLabel = new GridData();
		gdStatusLabel.grabExcessHorizontalSpace = true;
		gdStatusLabel.horizontalAlignment = SWT.FILL;
		gdStatusLabel.verticalIndent = 3;
		gdStatusLabel.verticalAlignment = SWT.BEGINNING;
		statusLabel.setLayoutData(gdStatusLabel);

		viewer = new ActivityTreeViewer(main, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);

		// Make the tree view provide selections
		getSite().setSelectionProvider(viewer);
		contentProvider = new ContentProvider();
		contentProvider.setShowAllTasks(TimekeeperUiPlugin.getDefault().getPreferenceStore()
				.getBoolean(PreferenceConstants.SHOW_ALL_TASKS));
		TimekeeperUiPlugin.getDefault().addBackendListener(contentProvider);
		viewer.setContentProvider(contentProvider);
		GridData layoutData = GridDataFactory.fillDefaults().grab(true, true).span(3, 1).create();
		viewer.getControl().setLayoutData(layoutData);

		// create the time columns
		createTitleColumn();
		for (int i = 0; i < 7; i++) {
			createTimeColumn(i);
		}

		Tree tree = viewer.getTree();
		viewer.setComparator(new ViewerComparatorExtension());
		viewer.setAutoExpandLevel(AbstractTreeViewer.ALL_LEVELS);
		viewer.setComparer(new org.eclipse.jface.viewers.IElementComparer() {
			private Object key(Object element) {
				if (element instanceof Activity activity) return activity.id();
				if (element instanceof Task task) return task.id();
				if (element instanceof Project project) return project.id();
				return element;
			}
			@Override public int hashCode(Object element) { return key(element).hashCode(); }
			@Override public boolean equals(Object left, Object right) { return key(left).equals(key(right)); }
		});
		ColumnViewerToolTipSupport.enableFor(viewer, ToolTip.NO_RECREATE);
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);
		tree.setData("org.eclipse.swtbot.widget.key", "workweek-editor-tree");

		activityLabelPainter = new ActivityLabelPainter();
		tree.addListener(SWT.PaintItem, new Listener() {

			@Override
			public void handleEvent(Event event) {
				TreeItem treeItem = (TreeItem) event.item;
				if (treeItem.getData() instanceof Activity && event.index == 0) {
					var bounds = treeItem.getBounds(0);
					int offset = 0;
					List<net.resheim.eclipse.timekeeper.domain.Label> labels = contentProvider.labels((Activity) treeItem.getData());
					for (var label : labels) {
						Image image = activityLabelPainter.getLabelImage(label, 16, true);
						int x = bounds.x + bounds.width - image.getBounds().width;
						int itemHeight = tree.getItemHeight();
						int imageHeight = image.getBounds().height;
						int y = event.y + (itemHeight - imageHeight) / 2;
						event.gc.drawImage(image, x + offset, y);
						offset -= 12;
					}
				}
			}

		});

		// adjust column widths when view is resized
		main.addControlListener(new ControlAdapter() {
			int previous;

			@Override
			public void controlResized(ControlEvent e) {
				Rectangle area = main.getBounds();
				int width = area.width;
				if (width != previous) {
					TreeColumn[] columns = tree.getColumns();
					int cwidth = 0;
					for (int i = 1; i < columns.length; i++) {
						columns[i].pack();
						if (columns[i].getWidth() < TIME_COLUMN_WIDTH) {
							columns[i].setWidth(TIME_COLUMN_WIDTH);
						}
						cwidth += columns[i].getWidth();
					}
					// set the width of the first column
					columns[0].setWidth(width - cwidth);
					previous = width;
				}
			}
		});

		// Determine the first date of the week
		LocalDate date = LocalDate.now(TimekeeperUiPlugin.getCalendarZone());
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		long day = date.get(weekFields.dayOfWeek());
		contentProvider.setFirstDayOfWeek(date.minusDays(day - 1));

		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();
		viewer.setInput(getViewSite());
		// Force a redraw so content is visible
		main.pack();
		installStatusUpdater();
	}

	private TreeViewerColumn createTableViewerColumn(String title, int width, final int colNumber) {
		final TreeViewerColumn viewerColumn = new TreeViewerColumn(viewer, SWT.NONE);
		TreeColumn column = viewerColumn.getColumn();
		column.setText(title);
		column.setWidth(width);
		column.setResizable(true);
		column.setMoveable(false);
		return viewerColumn;
	}

	/**
	 * Creates a table column for the given weekday and installs editing support
	 *
	 * @param weekday
	 *            the day of the week, starting from 0
	 */
	private void createTimeColumn(int weekday) {
		TreeViewerColumn column = createTableViewerColumn("-", TIME_COLUMN_WIDTH, 1 + weekday);
		column.getColumn().setAlignment(SWT.RIGHT);
		column.setEditingSupport(new TimeEditingSupport((TreeViewer) column.getViewer(), contentProvider, weekday));
		column.setLabelProvider(new TimeColumnLabelProvider(contentProvider) {

			@Override
			public String getText(Object element) {
				LocalDate date = contentProvider.getFirstDayOfWeek().plusDays(weekday);
				long seconds = contentProvider.seconds(element, date);
				if (seconds > 0) {
					return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
				}
				return "";
			}
		});
	}

	private void createTitleColumn() {
		TreeViewerColumn column = createTableViewerColumn("Activity", 400, 1);
		column.setLabelProvider(new TreeColumnViewerLabelProvider(new TitleColumnLabelProvider(contentProvider)));
		column.setEditingSupport(new ActivitySummaryEditingSupport(viewer, contentProvider));
	}

	@Override
	public void dispose() {
		activityLabelPainter.disposeImages();
		super.dispose();
	}

	/**
	 * Populates the view context menu.
	 */
	private void fillContextMenu(IMenuManager manager) {
		manager.add(previousWeekAction);
		manager.add(currentWeekAction);
		manager.add(nextWeekAction);
		if (!TimekeeperUiPlugin.getDefault().isBackendReady()) return;
		manager.add(new Separator("create"));
		manager.add(newProjectAction);
		manager.add(newTaskAction);
		Object selected = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
		if (selected instanceof Project project) {
			manager.add(new Separator("project"));
			manager.add(new Action("Edit project...") {
				@Override public void run() { editProject(project); }
			});
			manager.add(new Action("Delete project...") {
				@Override public void run() {
					if (org.eclipse.jface.dialogs.MessageDialog.openConfirm(getSite().getShell(), "Delete project",
							"Delete project '" + project.name() + "'?")) {
						perform(() -> TimekeeperUiPlugin.getDefault().getTimekeeperService()
								.deleteProject(new DeleteProject(project.id(), project.version())));
					}
				}
			});
		}
		if (selected instanceof Task task) {
			manager.add(new Separator("task"));
			Action newSubtask = new Action("New subtask...") {
				@Override public void run() { editTask(null, task.projectId(), Optional.of(task.id())); }
			};
			newSubtask.setEnabled(task.projectId().isPresent());
			manager.add(newSubtask);
			manager.add(new Action("Edit task...") {
				@Override public void run() { editTask(task, task.projectId(), task.parentTaskId()); }
			});
			manager.add(new Action("Delete task...") {
				@Override public void run() {
					if (org.eclipse.jface.dialogs.MessageDialog.openConfirm(getSite().getShell(), "Delete task",
							"Delete task '" + task.summary() + "'?")) {
						perform(() -> TimekeeperUiPlugin.getDefault().getTimekeeperService()
								.deleteTask(new DeleteTask(task.id(), task.version())));
					}
				}
			});
			manager.add(isTaskActive(task) ? deactivateAction : activateAction);
			manager.add(newActivityAction);
		}
		if (selected instanceof Activity activity) {
			manager.add(new Separator("labels"));
			manager.add(new Separator("activity"));
			manager.add(new Action("Edit activity...") {
				@Override public void run() {
					ActivityDialog dialog = new ActivityDialog(getSite().getShell(),
							TimekeeperUiPlugin.getDefault().getTimekeeperService(), activity,
							contentProvider.getFirstDayOfWeek());
					dialog.open();
					refreshAll();
				}
			});
			if (activity.end().isEmpty()) manager.add(new Action("Stop activity") {
				@Override public void run() { perform(() -> TimekeeperUiPlugin.getDefault()
						.getTimekeeperService().stopActivity(
								new StopActivity(activity.id(), activity.version()))); }
			});
			else manager.add(deleteAction);
		}
	}

    private void perform(Runnable action) {
        TimekeeperUiPlugin.getDefault().runOperation("Update Timekeeper",()->{action.run();return null;},value->refreshAll(),failure->{
            if(!viewer.getControl().isDisposed()) org.eclipse.jface.dialogs.MessageDialog.openError(getSite().getShell(),"Timekeeper",ActivityDialog.failureMessage(failure));
            refreshAll();
        });
    }

    private void performOnUi(Runnable action) {
        try {action.run();refreshAll();}
        catch(RuntimeException failure) {
            org.eclipse.jface.dialogs.MessageDialog.openError(getSite().getShell(),"Timekeeper",ActivityDialog.failureMessage(failure));
        }
    }

	private boolean isTaskActive(Task task) {
		var plugin = TimekeeperUiPlugin.getDefault();
		return plugin.getTimekeeperService().activeActivity(OwnerId.LOCAL)
				.filter(activity -> activity.taskId().equals(task.id())).isPresent()
				|| plugin.getTaskIntegration().filter(integration -> integration.isActive(task)).isPresent();
	}

	private void activateTask(Task task) {
		var plugin = TimekeeperUiPlugin.getDefault();
		var service = plugin.getTimekeeperService();
		Task current = service.task(task.id()).orElseThrow();
		var linked = plugin.getTaskIntegration().filter(integration -> integration.isLinked(current));
		if (linked.isPresent()) {
			linked.orElseThrow().activate(current);
			return;
		}
		// Provider activation can outlive an interval stopped directly in Workweek.
		plugin.getTaskIntegration().ifPresent(integration -> integration.deactivateActiveTask());
		var active = service.activeActivity(OwnerId.LOCAL);
		if (active.filter(activity -> activity.taskId().equals(current.id())).isPresent()) return;
		        if(plugin.isRemote()) {
            plugin.activateRemoteTask(current.id(),active);
        } else {
            active.ifPresent(activity -> service.stopActivity(new StopActivity(activity.id(), activity.version())));
            service.startActivity(new StartActivity(current.id(),OwnerId.LOCAL,"",Set.of()));
        }
	}

	private void deactivateTask(Task task) {
		var plugin = TimekeeperUiPlugin.getDefault();
		plugin.getTaskIntegration().filter(integration -> integration.isLinked(task))
				.ifPresent(integration -> integration.deactivate(task.id()));
        plugin.getTimekeeperService().activeActivity(OwnerId.LOCAL).filter(activity -> activity.taskId().equals(task.id()))
                .ifPresent(activity -> perform(()->plugin.getTimekeeperService().stopActivity(new StopActivity(activity.id(),activity.version()))));
	}

	private void fillLocalPullDown(IMenuManager manager) {
        manager.add(new Action("Reconnect / refresh backend") {@Override public void run() {TimekeeperUiPlugin.getDefault().refreshBackend();}});
		manager.add(newProjectAction);
		manager.add(newTaskAction);
		manager.add(new Separator("filter"));
		manager.add(showAllTasksAction);
		manager.add(new Separator("tree"));
		manager.add(collapseAllAction);
		manager.add(expandAllAction);
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(new Separator("additions"));
		manager.add(newProjectAction);
		manager.add(newTaskAction);
		manager.add(new Separator("navigation"));
		manager.add(previousWeekAction);
		manager.add(currentWeekAction);
		manager.add(nextWeekAction);
		manager.add(new Separator("filter"));
		manager.add(showAllTasksAction);
		manager.add(new Separator("tree"));
		manager.add(collapseAllAction);
		manager.add(expandAllAction);
	}

	private String getFormattedPeriod(long seconds) {
		if (seconds > 0) {
			return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
		}
		// TODO: Fix dates with no entries "0:00"
		return "";
	}


	private void hookContextMenu() {
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				WorkWeekView.this.fillContextMenu(manager);
			}
		});
		// create a context menu for the view
		Menu menu = menuMgr.createContextMenu(viewer.getControl());
		viewer.getControl().setMenu(menu);
		getSite().registerContextMenu(menuMgr, viewer);
	}

	private void hookDoubleClickAction() {
		viewer.addDoubleClickListener(new IDoubleClickListener() {
			public void doubleClick(DoubleClickEvent event) {
				doubleClickAction.run();
			}
		});
	}

	/**
	 * @return the first day of the week displayed
	 */
	public LocalDate getFirstDayOfWeek() {
		return contentProvider.getFirstDayOfWeek();
	}

	private void makeActions() {
		showAllTasksAction = new Action("Show all tasks", IAction.AS_CHECK_BOX) {
			@Override public void run() {
				contentProvider.setShowAllTasks(isChecked());
				TimekeeperUiPlugin.getDefault().getPreferenceStore()
						.setValue(PreferenceConstants.SHOW_ALL_TASKS, isChecked());
				refreshAll();
			}
		};
		showAllTasksAction.setChecked(TimekeeperUiPlugin.getDefault().getPreferenceStore()
				.getBoolean(PreferenceConstants.SHOW_ALL_TASKS));
		showAllTasksAction.setToolTipText("Show all tasks, including those without time in the displayed week");
		collapseAllAction = new Action("Collapse all") {
			@Override public void run() { viewer.collapseAll(); }
		};
		collapseAllAction.setToolTipText("Collapse all projects and tasks");
		collapseAllAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_ELCL_COLLAPSEALL));
		expandAllAction = new Action("Expand all") {
			@Override public void run() { viewer.expandAll(); }
		};
		expandAllAction.setToolTipText("Expand all projects and tasks");
		expandAllAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_ELCL_EXPANDALL));
		newProjectAction = new Action("New project...") {
			@Override public void run() { editProject(null); }
		};
		newProjectAction.setToolTipText("Create a Timekeeper project");
		newProjectAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER));
		newTaskAction = new Action("New task...") {
			@Override public void run() {
				Object selected = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
				Optional<net.resheim.eclipse.timekeeper.domain.ProjectId> projectId = selected instanceof Project project
						? Optional.of(project.id()) : selected instanceof Task task ? task.projectId() : Optional.empty();
				editTask(null, projectId, Optional.empty());
			}
		};
		newTaskAction.setToolTipText("Create a Timekeeper task");
		newTaskAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_FILE));
		// browse to previous week
		previousWeekAction = new Action() {
			@Override
			public void run() {
				contentProvider.setFirstDayOfWeek(contentProvider.getFirstDayOfWeek().minusDays(7));
				viewer.setInput(getViewSite());
			}
		};
		previousWeekAction.setText("Previous week");
		previousWeekAction.setToolTipText("Show previous week");
		previousWeekAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_TOOL_BACK));

		// browse to current week
		currentWeekAction = new Action() {
			@Override
			public void run() {
				contentProvider.setFirstDayOfWeek(calculateFirstDayOfWeek(LocalDate.now(TimekeeperUiPlugin.getCalendarZone())));
				viewer.setInput(getViewSite());
			}
		};
		currentWeekAction.setText("Current week");
		currentWeekAction.setToolTipText("Show current week");
		currentWeekAction
		.setImageDescriptor(TimekeeperUiPlugin.getDefault()
				.getImageRegistry()
				.getDescriptor(TimekeeperUiPlugin.IMG_TOOL_CURRENT));

		// browse to next week
		nextWeekAction = new Action() {
			@Override
			public void run() {
				contentProvider.setFirstDayOfWeek(contentProvider.getFirstDayOfWeek().plusDays(7));
				viewer.setInput(getViewSite());
			}
		};
		nextWeekAction.setText("Next week");
		nextWeekAction.setToolTipText("Show next week");
		nextWeekAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_TOOL_FORWARD));

		// double click on task
		doubleClickAction = new Action() {
			@Override
			public void run() {
				if (!TimekeeperUiPlugin.getDefault().isBackendReady()) return;
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof Task) {
					Task task = (Task) obj;
					var integration = TimekeeperUiPlugin.getDefault().getTaskIntegration().filter(value -> value.isLinked(task));
					if (integration.isPresent()) integration.orElseThrow().open(task);
					else editTask(task, task.projectId(), task.parentTaskId());
				} else if (obj instanceof Project project) {
					editProject(project);
				}
			}
		};

		// deactivate task
		deactivateAction = new Action("Deactivate task") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof Task) {
					performOnUi(() -> deactivateTask((Task)obj));
				}
			}
		};

		// activate task
		activateAction = new Action("Activate task") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof Task) {
					performOnUi(() -> activateTask((Task)obj));
				}
			}
		};

		// A recorded interval can be added to any task and any date.
		newActivityAction = new Action("New activity") {
			@Override public void run() {
				Object selected = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
				if (selected instanceof Task task) {
					new ActivityDialog(getSite().getShell(), TimekeeperUiPlugin.getDefault().getTimekeeperService(),
							task, contentProvider.getFirstDayOfWeek()).open();
					refreshAll();
				}
			}
		};
		deleteAction = new Action("Delete activity") {
			@Override public void run() {
				var selected = ((IStructuredSelection) viewer.getSelection()).toList();
				perform(() -> {
					for (Object element : selected) if (element instanceof Activity activity && activity.end().isPresent()) {
						TimekeeperUiPlugin.getDefault().getTimekeeperService().deleteActivity(
								new DeleteActivity(activity.id(), activity.version()));
					}
				});
			}
		};

		deleteAction.setImageDescriptor(
				PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_TOOL_DELETE));

	}

	private void editProject(Project original) {
		ProjectDialog dialog = new ProjectDialog(getSite().getShell(), TimekeeperUiPlugin.getDefault().getTimekeeperService(), original);
		if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.savedProject() != null
                && !viewer.getControl().isDisposed()) {
			refreshAll();
			viewer.setSelection(new StructuredSelection(dialog.savedProject()), true);
		}
	}

	private void editTask(Task original, Optional<net.resheim.eclipse.timekeeper.domain.ProjectId> projectId,
			Optional<net.resheim.eclipse.timekeeper.domain.TaskId> parentId) {
		TaskDialog dialog = new TaskDialog(getSite().getShell(), TimekeeperUiPlugin.getDefault().getTimekeeperService(), original, projectId, parentId);
		if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.savedTask() != null
                && !viewer.getControl().isDisposed()) {
			refreshAll();
			viewer.setSelection(new StructuredSelection(dialog.savedTask()), true);
		}
	}

	/**
	 * Passing the focus request to the viewer's control.
	 */
	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
		IContextService contextService = PlatformUI.getWorkbench().getService(IContextService.class);
		contextService.activateContext("net.resheim.eclipse.timekeeper.ui.workweek");
	}

	/**
	 * Used to notify the view that the content have had an massive change.
	 * Typically after importing a number of records.
	 */
	public void refreshAll() {
        // A modal editor can return after its view was closed during workbench shutdown.
        if (viewer == null || viewer.getControl().isDisposed() || viewer.getContentProvider() == null)
            return;
		viewer.getContentProvider().inputChanged(viewer, viewer.getInput(), null);
	}

	public void refresh(Object element) {
        refreshAll();
	}
}
