/*******************************************************************************
 * Copyright (c) 2014 Torkild U. Resheim.
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import net.resheim.eclipse.timekeeper.ui.Activator;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreeColumnViewerLabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.mylyn.commons.ui.CommonImages;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.LocalTask;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskGroup;
import org.eclipse.mylyn.internal.tasks.core.UncategorizedTaskContainer;
import org.eclipse.mylyn.internal.tasks.core.UnsubmittedTaskContainer;
import org.eclipse.mylyn.internal.tasks.ui.TasksUiPlugin;
import org.eclipse.mylyn.tasks.core.IRepositoryElement;
import org.eclipse.mylyn.tasks.core.IRepositoryQuery;
import org.eclipse.mylyn.tasks.core.ITask;
import org.eclipse.mylyn.tasks.core.ITaskActivationListener;
import org.eclipse.mylyn.tasks.core.ITaskContainer;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.eclipse.mylyn.tasks.core.data.TaskAttribute;
import org.eclipse.mylyn.tasks.core.data.TaskData;
import org.eclipse.mylyn.tasks.ui.AbstractRepositoryConnectorUi;
import org.eclipse.mylyn.tasks.ui.TasksUi;
import org.eclipse.mylyn.tasks.ui.TasksUiImages;
import org.eclipse.mylyn.tasks.ui.TasksUiUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DateTime;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.ViewPart;

@SuppressWarnings("restriction")
public class WorkWeekView extends ViewPart {

	private class TaskLabelProvider extends LabelProvider implements IFontProvider, ILabelProvider {

		@Override
		public Font getFont(Object element) {
			if (element instanceof ITask){
				if (((ITask) element).isActive()) {
					return JFaceResources.getFontRegistry().getBold(
							JFaceResources.DIALOG_FONT);
				}
			}
			if (element instanceof String){
				String p = (String) element;
				if (TasksUiPlugin.getTaskList().getAllTasks()
						.stream()
						.filter(t -> t.getAttribute(Activator.ATTR_ID) != null)
						.filter(t -> p.equals(Activator.getProjectName(t)))
						.anyMatch(t -> t.isActive())){
					return JFaceResources.getFontRegistry().getBold(
							JFaceResources.DIALOG_FONT);
				}

			}
			return JFaceResources.getDialogFont();
		}

		private class CompositeImageDescriptor {
			ImageDescriptor icon;
			ImageDescriptor overlayKind;
		}

		@Override
		public Image getImage(Object element) {
			CompositeImageDescriptor compositeDescriptor = getImageDescriptor(element);
			if (element instanceof ITask) {
				if (compositeDescriptor.overlayKind == null) {
					compositeDescriptor.overlayKind = CommonImages.OVERLAY_CLEAR;
				}
				return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, compositeDescriptor.overlayKind,
						false);
			} else if (element instanceof ITaskContainer) {
				return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, CommonImages.OVERLAY_CLEAR, false);
			} else {
				return CommonImages.getCompositeTaskImage(compositeDescriptor.icon, null, false);
			}
		}

		private CompositeImageDescriptor getImageDescriptor(Object object) {
			CompositeImageDescriptor compositeDescriptor = new CompositeImageDescriptor();
			if (object instanceof UncategorizedTaskContainer) {
				compositeDescriptor.icon = TasksUiImages.CATEGORY_UNCATEGORIZED;
				return compositeDescriptor;
			} else if (object instanceof UnsubmittedTaskContainer) {
				compositeDescriptor.icon = TasksUiImages.CATEGORY_UNCATEGORIZED;
				return compositeDescriptor;
			} else if (object instanceof TaskCategory) {
				compositeDescriptor.icon = TasksUiImages.CATEGORY;
			} else if (object instanceof TaskGroup) {
				compositeDescriptor.icon = CommonImages.GROUPING;
			}

			if (object instanceof ITaskContainer) {
				IRepositoryElement element = (IRepositoryElement) object;

				AbstractRepositoryConnectorUi connectorUi = null;
				if (element instanceof ITask) {
					ITask repositoryTask = (ITask) element;
					connectorUi = TasksUiPlugin.getConnectorUi(((ITask) element).getConnectorKind());
					if (connectorUi != null) {
						compositeDescriptor.overlayKind = connectorUi.getTaskKindOverlay(repositoryTask);
					}
				} else if (element instanceof IRepositoryQuery) {
					connectorUi = TasksUiPlugin.getConnectorUi(((IRepositoryQuery) element).getConnectorKind());
				}

				if (connectorUi != null) {
					compositeDescriptor.icon = connectorUi.getImageDescriptor(element);
					return compositeDescriptor;
				} else {
					compositeDescriptor.icon = TasksUiImages.TASK;
					return compositeDescriptor;
				}
			}
			return compositeDescriptor;
		}

		@Override
		public String getText(Object element) {
			if (element instanceof String) {
				return (String) element;
			}
			ITask task = ((ITask) element);
			StringBuilder sb = new StringBuilder();
			if (task.getTaskKey() != null) {
				sb.append(task.getTaskKey());
				sb.append(": ");
			}
			sb.append(task.getSummary());
			return sb.toString();
		}

	}

	private final class TaskActivationListener implements ITaskActivationListener {
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
			viewer.refresh();
		}

		@Override
		public void taskDeactivated(ITask task) {
			viewer.refresh();
		}
	}

	private class TimeEditingSupport extends EditingSupport {

		int weekday;

		public TimeEditingSupport(ColumnViewer viewer, int weekday) {
			super(viewer);
			this.weekday = weekday;
		}

		@Override
		protected boolean canEdit(Object element) {
			if (element instanceof ITask) {
				return true;
			}
			return false;
		}

		@Override
		protected CellEditor getCellEditor(Object element) {
			return new TextCellEditor(viewer.getTree());
		}

		@Override
		protected Object getValue(Object element) {
			if (element instanceof ITask) {
				AbstractTask task = (AbstractTask) element;
				int seconds = Activator.getIntValue(task, getDateString(weekday));
				if (seconds > 0) {
					return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm", true);
				}
				return "0:00";
			}
			return "";
		}

		@Override
		protected void setValue(Object element, Object value) {
			if (element instanceof AbstractTask) {
				AbstractTask task = (AbstractTask) element;
				if (value instanceof String) {
					String[] split = ((String) value).split(":");
					int newValue = 0;
					// Only minutes are given
					if (split.length == 1) {
						newValue = Integer.parseInt(split[0]) * 60;
						Activator.setValue(task, getDateString(weekday), Integer.toString(newValue));
					}
					if (split.length == 2) {
						newValue = Integer.parseInt(split[0]) * 3600 + Integer.parseInt(split[1]) * 60;
						Activator.setValue(task, getDateString(weekday), Integer.toString(newValue));
					}
					// If the new value is 0, the task may have no time
					// logged for the week and should be removed.
					if (newValue == 0) {
						viewer.refresh();
					} else {
						viewer.update(element, null);
						viewer.update(Activator.getProjectName(task), null);
					}
				}
			}
		}

	}

	private class ViewContentProvider implements ITreeContentProvider {

		public void dispose() {
		}

		@Override
		public Object[] getChildren(Object parentElement) {
			// TODO: Maybe reuse calculation from getElements(Object)?
			if (parentElement instanceof String) {
				String p = (String) parentElement;
				return TasksUiPlugin.getTaskList().getAllTasks()
						.stream()
						.filter(t -> t.getAttribute(Activator.ATTR_ID) != null)
						.filter(t -> p.equals(Activator.getProjectName(t)))
						.filter(t -> hasData(t, firstDayOfWeek) || t.isActive())
						.toArray(size -> new AbstractTask[size]);
			}
			return new Object[0];
		}

		public Object[] getElements(Object parent) {
			return TasksUiPlugin.getTaskList().getAllTasks()
					.stream()
					.filter(t -> t.getAttribute(Activator.ATTR_ID) != null)
					.filter(t -> hasData(t, firstDayOfWeek) || t.isActive())
					.collect(Collectors.groupingBy(t -> Activator.getProjectName(t)))
					.keySet().toArray();
		}

		@Override
		public Object getParent(Object element) {
			if (element instanceof ITask) {
				return Activator.getProjectName((AbstractTask) element);
			}
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			// Projects are guaranteed to have tasks as children, tasks are
			// guaranteed to not have any children.
			if (element instanceof String) {
				return true;
			}
			return false;
		}

		private boolean hasData(AbstractTask task, LocalDate startDate) {
			int sum = 0;
			for (int i = 0; i < 7; i++) {
				String ds = startDate.plusDays(i).toString();
				sum += Activator.getIntValue(task, ds);
			}
			return sum > 0;
		}

		public void inputChanged(Viewer v, Object oldInput, Object newInput) {
			v.refresh();
			// Also update the week label
			StringBuilder sb = new StringBuilder();
			sb.append("Showing week ");
			sb.append(firstDayOfWeek.format(weekFormat));
			sb.append(" starting at");
			dateTimeLabel.setText(sb.toString());
			dateChooser.setDate(firstDayOfWeek.getYear(), firstDayOfWeek.getMonthValue() - 1,
					firstDayOfWeek.getDayOfMonth());
			TreeColumn[] columns = viewer.getTree().getColumns();
			String[] headings = Activator.getDefault().getHeadings(firstDayOfWeek);
			for (int i = 1; i < columns.length; i++) {
				columns[i].setText(headings[i - 1]);
			}
		}
	}

	public static final String ID = "net.resheim.eclipse.timekeeper.ui.views.SampleView";

	private static final DateTimeFormatter weekFormat = DateTimeFormatter.ofPattern("w");

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
	private static int getSum(LocalDate date, String project) {
		final String d = date.toString();
		return TasksUiPlugin.getTaskList().getAllTasks()
				.stream()
				.filter(t -> t.getAttribute(Activator.ATTR_ID) != null)
				.filter(t -> project.equals(Activator.getProjectName(t))).mapToInt(t -> Activator.getIntValue(t, d))
				.sum();
	}

	private Action previousWeekAction;

	private Action nextWeekAction;

	private Action doubleClickAction;

	private MenuManager projectFieldMenu;

	private LocalDate firstDayOfWeek;

	private TaskActivationListener taskActivationListener;

	private TreeViewer viewer;

	private Text dateTimeLabel;

	private DateTime dateChooser;

	private Action deactivateAction;

	private Action activateAction;

	/**
	 * The constructor.
	 */
	public WorkWeekView() {
		// Determine the first date of the week
		LocalDate date = LocalDate.now();
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		int day = date.get(weekFields.dayOfWeek());
		firstDayOfWeek = date.minusDays(day - 1);
	}
	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	@Override
	public void createPartControl(Composite parent) {
		FormToolkit ft = new FormToolkit(parent.getDisplay());
		ScrolledComposite root = new ScrolledComposite(parent, SWT.V_SCROLL);
		ft.adapt(root);
		GridLayout layout = new GridLayout();
		root.setLayout(layout);
		root.setExpandHorizontal(true);
		root.setExpandVertical(true);

		Composite main = ft.createComposite(root);
		ft.adapt(main);
		root.setContent(main);
		GridLayout layout2 = new GridLayout(2, false);
		main.setLayout(layout2);

		dateTimeLabel = new Text(main, SWT.NONE);
		GridData gdLabel = new GridData();
		gdLabel.verticalIndent = 2;
		gdLabel.verticalAlignment = SWT.BEGINNING;
		dateTimeLabel.setLayoutData(gdLabel);

		dateChooser = new DateTime(main, SWT.DROP_DOWN | SWT.DATE | SWT.LONG);
		GridData gdChooser = new GridData();
		gdChooser.verticalAlignment = SWT.BEGINNING;
		dateChooser.setLayoutData(gdChooser);
		ft.adapt(dateChooser);
		dateChooser.addSelectionListener(new SelectionAdapter() {

			@Override
			public void widgetSelected(SelectionEvent event) {
				// Determine the first date of the week
				LocalDate date = LocalDate.of(dateChooser.getYear(), dateChooser.getMonth() + 1, dateChooser.getDay());
				WeekFields weekFields = WeekFields.of(Locale.getDefault());
				int day = date.get(weekFields.dayOfWeek());
				firstDayOfWeek = date.minusDays(day - 1);
				viewer.setInput(this);
			}
		});

		viewer = new TreeViewer(main, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
		viewer.setContentProvider(new ViewContentProvider());
		GridData layoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
		layoutData.horizontalSpan = 2;
		viewer.getControl().setLayoutData(layoutData);

		createTitleColumn();
		for (int i = 0; i < 7; i++) {
			createTimeColumn(i);
		}

		viewer.setComparator(new ViewerComparator() {

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
				return super.compare(viewer, e1, e2);
			}
		});

		viewer.getTree().setHeaderVisible(true);

		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();
		taskActivationListener = new TaskActivationListener();
		TasksUiPlugin.getTaskActivityManager().addActivationListener(taskActivationListener);
		viewer.setInput(getViewSite());
		// Force a redraw so content is visible
		root.pack();
	}

	private TreeViewerColumn createTableViewerColumn(String title, int bound, final int colNumber) {
		final TreeViewerColumn viewerColumn = new TreeViewerColumn(viewer, SWT.NONE);
		TreeColumn column = viewerColumn.getColumn();
		column.setText(title);
		column.setWidth(bound);
		column.setResizable(true);
		column.setMoveable(true);
		return viewerColumn;
	}

	private void createTimeColumn(int weekday) {
		TreeViewerColumn column = createTableViewerColumn("-", 50, 1 + weekday);
		column.getColumn().setMoveable(false);
		column.getColumn().setAlignment(SWT.RIGHT);
		column.setEditingSupport(new TimeEditingSupport(column.getViewer(), weekday));
		column.setLabelProvider(new ColumnLabelProvider() {

			@Override
			public String getText(Object element) {
				int seconds = 0;
				if (element instanceof String) {
					LocalDate date = firstDayOfWeek.plusDays(weekday);
					seconds = getSum(date, (String) element);
				} else {
					AbstractTask task = (AbstractTask) element;
					seconds = Activator.getIntValue(task, getDateString(weekday));
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
		column.setLabelProvider(new TreeColumnViewerLabelProvider(new TaskLabelProvider()));
	};

	@Override
	public void dispose() {
		TasksUiPlugin.getTaskActivityManager().removeActivationListener(taskActivationListener);
		super.dispose();
	}

	private void fillContextMenu(IMenuManager manager) {
		manager.add(previousWeekAction);
		manager.add(nextWeekAction);
		ISelection selection = viewer.getSelection();
		Object obj = ((IStructuredSelection) selection).getFirstElement();
		if (obj instanceof ITask) {
			if (((ITask) obj).isActive()) {
				manager.add(deactivateAction);
			} else {
				manager.add(activateAction);
			}
			manager.add(new Separator());
			manager.add(projectFieldMenu);
		}
	}

	private void fillLocalPullDown(IMenuManager manager) {
		// manager.add(action1);
		// manager.add(new Separator());
		// manager.add(action2);
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(previousWeekAction);
		manager.add(nextWeekAction);
	}

	/**
	 * Returns a string representation of the date.
	 *
	 * @param weekday
	 * @return
	 */
	private String getDateString(int weekday) {
		return firstDayOfWeek.plusDays(weekday).toString();
	}

	private void hookContextMenu() {
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				WorkWeekView.this.fillContextMenu(manager);
			}
		});
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

	private void makeActions() {
		previousWeekAction = new Action() {
			@Override
			public void run() {
				firstDayOfWeek = firstDayOfWeek.minusDays(7);
				viewer.setInput(getViewSite());
			}
		};
		previousWeekAction.setText("Previous Week");
		previousWeekAction.setToolTipText("Show previous week");
		previousWeekAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_TOOL_BACK));

		nextWeekAction = new Action() {
			@Override
			public void run() {
				firstDayOfWeek = firstDayOfWeek.plusDays(7);
				viewer.setInput(getViewSite());
			}
		};
		nextWeekAction.setText("Next Week");
		nextWeekAction.setToolTipText("Show next week");
		nextWeekAction.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_TOOL_FORWARD));

		doubleClickAction = new Action() {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof ITask) {
					TasksUiUtil.openTask((ITask) obj);
				}
			}
		};
		deactivateAction = new Action("Deactivate") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof ITask) {
					TasksUi.getTaskActivityManager().deactivateTask((ITask) obj);
				}
			}
		};
		activateAction = new Action("Activate") {
			@Override
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				if (obj instanceof ITask) {
					TasksUi.getTaskActivityManager().activateTask((ITask) obj);
				}
			}
		};

		projectFieldMenu = new MenuManager("Set Grouping Field", null);
		projectFieldMenu.setRemoveAllWhenShown(true);
		projectFieldMenu.addMenuListener(new IMenuListener() {

			public void menuAboutToShow(IMenuManager manager) {

				ISelection selection = viewer.getSelection();
				if (selection instanceof IStructuredSelection) {
					Object firstElement = ((IStructuredSelection) selection).getFirstElement();
					if (firstElement instanceof AbstractTask) {
						AbstractTask task = (AbstractTask) firstElement;
						// No way to change project on local tasks
						if (task instanceof LocalTask) {
							return;
						}
						String url = task.getRepositoryUrl();
						TaskRepository repository = TasksUiPlugin.getRepositoryManager().getRepository(url);
						try {
							TaskData taskData = TasksUi.getTaskDataManager().getTaskData(task);
							List<TaskAttribute> attributesByType = taskData.getAttributeMapper().getAttributesByType(
									taskData, TaskAttribute.TYPE_SINGLE_SELECT);
							// customfield_10410 = subproject
							for (TaskAttribute taskAttribute : attributesByType) {
								final String label = taskAttribute.getMetaData().getLabel();
								if (label != null) {
									final String id = taskAttribute.getId();
									Action a = new Action(label.replaceAll(":", "")) {
										@Override
										public void run() {
											setProjectField(repository, id);
										}

									};
									manager.add(a);
								}
							}
							manager.add(new Separator());
							Action a = new Action("Default") {
								@Override
								public void run() {
									setProjectField(repository, null);
								}
							};
							manager.add(a);
						} catch (CoreException e) {
							e.printStackTrace();
						}
					}
				}
			}
		});
	}

	private void setProjectField(TaskRepository repository, String string) {
		if (null == string) {
			repository.removeProperty(Activator.ATTR_ID + ".grouping");
		}
		repository.setProperty(Activator.ATTR_ID + ".grouping", string);
		viewer.refresh();
	}

	/**
	 * Passing the focus request to the viewer's control.
	 */
	@Override
	public void setFocus() {
		viewer.getControl().setFocus();
	}
}