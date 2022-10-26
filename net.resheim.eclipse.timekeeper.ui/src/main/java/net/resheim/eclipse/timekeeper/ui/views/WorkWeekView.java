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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.action.Action;
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
import org.eclipse.jface.viewers.TreeColumnViewerLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.ITaskListChangeListener;
import org.eclipse.mylyn.internal.tasks.core.TaskContainerDelta;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.mylyn.tasks.ui.TasksUiUtil;
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
import net.resheim.eclipse.timekeeper.db.model.Activity;
import net.resheim.eclipse.timekeeper.db.model.ActivityLabel;
import net.resheim.eclipse.timekeeper.db.model.Project;
import net.resheim.eclipse.timekeeper.db.model.Task;
import net.resheim.eclipse.timekeeper.ui.ActivityLabelPainter;
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
			if (e1 instanceof ITask && e2 instanceof ITask) {
				String s1 = ((ITask) e1).getTaskId();
				String s2 = ((ITask) e2).getTaskId();
				try {
					int i1 = Integer.parseInt(s1);
					int i2 = Integer.parseInt(s2);
					return i1 - i2;
				} catch (NumberFormatException e) {

				}
				return s1.compareTo(s2);
			}
			if (e1 instanceof WeeklySummary) {
				return 1;
			}
			if (e1 instanceof Activity && e2 instanceof Activity) {
				return ((Activity) e1).compareTo((Activity) e2);
			}
			return super.compare(viewer, e1, e2);
		}

	}

	private final class TaskListener implements ITaskActivationListener, ITaskListChangeListener {

		@Override
		public void containersChanged(Set<TaskContainerDelta> arg0) {
			updateAll();
		}

		@Override
		public void preTaskActivated(ITask task) {
			// Do nothing
		}

		@Override
		public void preTaskDeactivated(ITask task) {
			// Do nothing
		}

		@Override
		public void taskActivated(ITask task) {
			updateAll();
		}

		@Override
		public void taskDeactivated(ITask task) {
			updateAll();
		}

		private void updateAll() {
			getSite().getShell().getDisplay().asyncExec(new Runnable() {

				@Override
				public void run() {
					if (!viewer.getControl().isDisposed() && !viewer.isCellEditorActive()) {
						viewer.setInput(getViewSite());
					}
				}
			});
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
		LocalDateTime activeSince = TimekeeperUiPlugin.getDefault().getActiveSince();
		if (activeSince != null) {
			LocalDateTime now = LocalDateTime.now();
			return activeSince.until(now, ChronoUnit.MILLIS);
		}
		return 0;
	}

	private void updateStatus() {
		ITask activeTask = TasksUi.getTaskActivityManager().getActiveTask();
		if (activeTask == null) {
			statusLabel.setText("");
		} else if (TimekeeperUiPlugin.getDefault().isIdle()) {
			statusLabel.setText("Idle since " +
					timeFormat.format(TimekeeperUiPlugin.getDefault().getIdleSince()));
			// do not refresh with an editor active, that would deactivate the
			// editor and lose focus
			if (!viewer.isCellEditorActive()) {
				viewer.refresh(activeTask);
				viewer.refresh(contentProvider.getParent(activeTask));
				viewer.refresh(WeekViewContentProvider.WEEKLY_SUMMARY);
			}
		} else if (getActiveTime() > 0) {
			long activeTime = getActiveTime();
			LocalDateTime activeSince = TimekeeperUiPlugin.getDefault().getActiveSince();
			statusLabel.setText(MessageFormat.format("Active since {0}, {1} elapsed", timeFormat.format(activeSince),
					DurationFormatUtils.formatDurationWords(activeTime, true, true)));
			// do not refresh with an editor active, that would deactivate the
			// editor and lose focus
			if (!viewer.isCellEditorActive()) {
				viewer.refresh(activeTask);
				viewer.refresh(contentProvider.getParent(activeTask));
				viewer.refresh(WeekViewContentProvider.WEEKLY_SUMMARY);
			}
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
			updateColumHeaders();
			filter();
			v.refresh();
		}

		private void updateColumHeaders() {
			TreeColumn[] columns = viewer.getTree().getColumns();
			String[] headings = TimekeeperUiPlugin.getDefault().getHeadings(getFirstDayOfWeek());
			for (int i = 1; i < columns.length; i++) {
				LocalDate date = getFirstDayOfWeek().plusDays((long) i - 1);
				columns[i].setText(headings[i - 1]);
				columns[i].setToolTipText(getFormattedPeriod(getSum(filtered, date)));
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

	private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("EEEE HH:mm:ss", Locale.ENGLISH);

	private Action previousWeekAction;

	private Action currentWeekAction;

	private Action newActivityAction;

	private Action nextWeekAction;

	private Action doubleClickAction;

	private Action deleteAction;

	private TaskListener taskListener;

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

		viewer = new TreeViewer(main, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);

		// Make the tree view provide selections
		getSite().setSelectionProvider(viewer);
		contentProvider = new ContentProvider();
		TimekeeperPlugin.getDefault().addListener(contentProvider);
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
		ColumnViewerToolTipSupport.enableFor(viewer, ToolTip.NO_RECREATE);
		tree.setHeaderVisible(true);
		tree.setLinesVisible(true);
		tree.setData("org.eclipse.swtbot.widget.key", "workweek-editor-tree");

		activityLabelPainter = new ActivityLabelPainter();
		tree.addListener(SWT.PaintItem, new Listener() {

			@Override
			public void handleEvent(Event event) {
				TreeItem treeItem = (TreeItem) event.item;
				int width = tree.getColumn(0).getWidth();
				if (treeItem.getData() instanceof Activity && event.index == 0) {
					int offset = 0;
					List<ActivityLabel> labels = ((Activity) treeItem.getData()).getLabels();
					for (ActivityLabel label : labels) {
						Image image = activityLabelPainter.getLabelImage(label, 16, true);
						int x = width - image.getBounds().width;
						int itemHeight = tree.getItemHeight();
						int imageHeight = image.getBounds().height;
						int y = event.y + (itemHeight - imageHeight) / 2;
						event.gc.drawImage(image, x + offset, y);
						offset -= 6;
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
		LocalDate date = LocalDate.now();
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		long day = date.get(weekFields.dayOfWeek());
		contentProvider.setFirstDayOfWeek(date.minusDays(day - 1));

		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();
		taskListener = new TaskListener();
		TasksUiPlugin.getTaskActivityManager().addActivationListener(taskListener);
		TasksUiPlugin.getTaskList().addChangeListener(taskListener);
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
				// Use modern formatting
				long seconds = 0;
				LocalDate date = contentProvider.getFirstDayOfWeek().plusDays(weekday);
				if (element instanceof String) {
					seconds = getSum(contentProvider.getFiltered(), date, (Project) element);
				} else if (element instanceof ITask) {
					AbstractTask task = (AbstractTask) element;
					Task trackedTask = TimekeeperPlugin.getDefault().getTask(task);
					if (trackedTask != null) {
						seconds = trackedTask.getDuration(contentProvider.getDate(weekday)).getSeconds();
					}
				} else if (element instanceof WeeklySummary) {
					seconds = getSum(contentProvider.getFiltered(), date);
				} else if (element instanceof Activity) {
					seconds = ((Activity) element).getDuration(date).getSeconds();
				}
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
		column.setEditingSupport(new ActivitySummaryEditingSupport(viewer));
	}

	@Override
	public void dispose() {
		TasksUiPlugin.getTaskActivityManager().removeActivationListener(taskListener);
		TasksUiPlugin.getTaskList().removeChangeListener(taskListener);
		activityLabelPainter.disposeImages();
		super.dispose();
	}

	/**
	 * Populates the view context menu.
	 */
	private void fillContextMenu(IMenuManager manager) {
		manager.add(previousWeekAction);
		manager.add(nextWeekAction);
		ISelection selection = viewer.getSelection();
		Object obj = ((IStructuredSelection) selection).getFirstElement();
		if (obj instanceof Task) {
			manager.add(new Separator("task"));
			if (((Task) obj).getMylynTask().isActive()) {
				manager.add(deactivateAction);
			} else {
				manager.add(activateAction);
			}
			manager.add(newActivityAction);
		}
		if (obj instanceof Activity) {
			manager.add(new Separator("labels"));
			manager.add(new Separator("activity"));
			Optional<Activity> currentActivity = ((Activity) obj).getTrackedTask().getCurrentActivity();
			// do not allow deleting an activity that is currently active
			if (!(currentActivity.isPresent() && currentActivity.get().equals(obj))) {
				manager.add(deleteAction);
			}
		}
	}

	private void fillLocalPullDown(IMenuManager manager) {
		// Use to populate the local pulldown menu
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(new Separator("additions"));
		manager.add(new Separator("navigation"));
		manager.add(previousWeekAction);
		manager.add(currentWeekAction);
		manager.add(nextWeekAction);
	}

	private String getFormattedPeriod(long seconds) {
		if (seconds > 0) {
			return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
		}
		// TODO: Fix dates with no entries "0:00"
		return "";
	}

	/**
	 * Calculates the total amount of seconds accumulated on specified date.
	 *
	 * @param date
	 *            the date to calculate for
	 * @return the total amount of seconds accumulated
	 */
	private long getSum(Set<Task> filtered, LocalDate date) {
		// May not have been initialised when first called.
		if (filtered == null) {
			return 0;
		}
		return filtered
				.stream()
				//.filter(t -> TimekeeperPlugin.getDefault().getTask(t) != null)
				.mapToLong(t -> t.getDuration(date).getSeconds())
				.sum();
	}

	/**
	 * Calculates the total amount of seconds accumulated on the project for the
	 * specified date.
	 *
	 * @param date
	 *            the date to calculate for
	 * @param project
	 *            the project to calculate for
	 * @return the total amount of seconds accumulated
	 */
	private long getSum(Set<Task> filtered, LocalDate date, Project project) {
		return filtered
				.stream()
				.filter(t -> t != null)
				.filter(t -> project.equals(t.getProject()))
				.mapToLong(t -> t.getDuration(date).getSeconds())
				.sum();
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
				contentProvider.setFirstDayOfWeek(calculateFirstDayOfWeek(LocalDate.now()));
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
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof Task) {
					TasksUiUtil.openTask(((Task) obj).getMylynTask());
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
					TasksUi.getTaskActivityManager().deactivateTask(((Task) obj).getMylynTask());
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
					TasksUi.getTaskActivityManager().activateTask(((Task) obj).getMylynTask());
				}
			}
		};

		// create a new activity
		newActivityAction = new Action("New activity") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof Task) {
					((Task) obj).endActivity();
					((Task) obj).startActivity();
					refreshAll();
				}
			}
		};

		// delete activity
		deleteAction = new Action("Delete activity") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Iterator<?> iterator = ((IStructuredSelection) selection).iterator();
				while (iterator.hasNext()) {
					Object i = iterator.next();
					if (i instanceof Activity) {
						((Activity) i).getTrackedTask().getActivities().remove(i);
					}
				}
				viewer.refresh();
			}
		};
		deleteAction.setImageDescriptor(
				PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_TOOL_DELETE));

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
		viewer.getContentProvider().inputChanged(viewer, viewer.getInput(), null);
		viewer.expandAll();
	}

	// XXX: Don't do expandAll() but be more targeted
	public void refresh(Object element) {
		viewer.getContentProvider().inputChanged(viewer, viewer.getInput(), null);
		viewer.expandAll();
	}
}