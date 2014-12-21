package net.resheim.eclipse.timekeeper.ui.views;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.resheim.eclipse.timekeeper.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.ui.Activator;

import org.apache.commons.lang.time.DurationFormatUtils;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.mylyn.commons.ui.CommonImages;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.AbstractTaskContainer;
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
import org.eclipse.mylyn.tasks.ui.AbstractRepositoryConnectorUi;
import org.eclipse.mylyn.tasks.ui.TasksUiImages;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * Associate task repository with project attribute
 * 
 * 
 * @author torkild
 *
 */
@SuppressWarnings("restriction")
public class TimeReportView extends ViewPart {

	private final class TaskActivationListener implements ITaskActivationListener {
		@Override
		public void taskDeactivated(ITask task) {
			viewer.setInput(getViewSite());
		}

		@Override
		public void taskActivated(ITask task) {
			viewer.setInput(getViewSite());
		}

		@Override
		public void preTaskDeactivated(ITask task) {
			// Do nothing
		}

		@Override
		public void preTaskActivated(ITask task) {
			// Do nothing
		}
	}

	class NameSorter extends ViewerSorter {
	}

	class ViewContentProvider implements ITreeContentProvider {
		public void dispose() {
			TasksUiPlugin.getTaskActivityManager().removeActivationListener(taskActivationListener);
		}

		/**
		 * Returns the name of the container holding the supplied task.
		 * 
		 * @param task
		 *            task to find the name for
		 * @return the name of the task
		 */
		private String getParentContainerSummary(AbstractTask task) {
			if (task.getParentContainers().size() > 0) {
				AbstractTaskContainer next = task.getParentContainers().iterator().next();
				return next.getSummary();
			}
			return null;
		}

		public Object[] getElements(Object parent) {
			Set<String> projects = new HashSet<>();
			Collection<AbstractTask> filteredTasks = new ArrayList<>();
			Collection<AbstractTask> allTasks = TasksUiPlugin.getTaskList().getAllTasks();
			for (AbstractTask task : allTasks) {
				if (task.getAttribute(TimekeeperPlugin.ATTR_ID) != null) {
					filteredTasks.add(task);
				}
			}
			for (AbstractTask task : filteredTasks) {
				String c = task.getConnectorKind();
				switch (c) {
				case "github":
				case "local":
					projects.add(getParentContainerSummary(task));
					break;
				case "bugzilla":
					projects.add(task.getAttribute("product"));
					break;
				}
			}
			return projects.toArray();
		}

		public void inputChanged(Viewer v, Object oldInput, Object newInput) {
			v.refresh();
		}

		@Override
		public Object[] getChildren(Object parentElement) {
			Collection<AbstractTask> filteredTasks = new ArrayList<>();
			if (parentElement instanceof String) {
				Collection<AbstractTask> allTasks = TasksUiPlugin.getTaskList().getAllTasks();
				for (AbstractTask task : allTasks) {
					if (task.getAttribute(TimekeeperPlugin.ATTR_ID) != null) {
						String c = task.getConnectorKind();
						switch (c) {
						case "github":
						case "local":
							if (parentElement.equals(getParentContainerSummary(task))) {
								filteredTasks.add(task);
							}
							break;
						case "bugzilla":
							if (parentElement.equals(task.getAttribute("product"))) {
								filteredTasks.add(task);
							}
							break;
						}
					}
				}
			}
			return filteredTasks.toArray();
		}

		@Override
		public Object getParent(Object element) {
			if (element instanceof ITask) {
				String c = ((AbstractTask) element).getConnectorKind();
				switch (c) {
				case "github":
				case "local":
					return getParentContainerSummary((AbstractTask) element);
				case "bugzilla":
					return ((ITask) element).getAttribute("product");
				default:
					break;
				}
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
	}

	public static final String ID = "net.resheim.eclipse.timekeeper.ui.views.SampleView";

	private Action action1;

	private Action action2;

	private Action doubleClickAction;

	private TreeViewer viewer;

	private TaskActivationListener taskActivationListener;

	/**
	 * The constructor.
	 */
	public TimeReportView() {
	}

	private void contributeToActionBars() {
		IActionBars bars = getViewSite().getActionBars();
		fillLocalPullDown(bars.getMenuManager());
		fillLocalToolBar(bars.getToolBarManager());
	}

	public void createPartControl(Composite parent) {
		viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION);
		viewer.setContentProvider(new ViewContentProvider());

		createTitleColumn();
		String[] headings = Activator.getDefault().getHeadings(LocalDate.now());
		for (int i = 0; i < 7; i++) {
			createTimeColumn(i, headings[i]);
		}

		viewer.setSorter(new NameSorter());
		viewer.setInput(getViewSite());

		viewer.getTree().setHeaderVisible(true);

		makeActions();
		hookContextMenu();
		hookDoubleClickAction();
		contributeToActionBars();
		taskActivationListener = new TaskActivationListener();
		TasksUiPlugin.getTaskActivityManager().addActivationListener(taskActivationListener);
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

	private void createTimeColumn(int weekday, String title) {
		TreeViewerColumn keyColumn = createTableViewerColumn(title, 50, 1 + weekday);
		keyColumn.setLabelProvider(new ColumnLabelProvider() {

			@Override
			public String getText(Object element) {
				if (element instanceof String) {
					// TODO: Return the sum for the project
					return "";
				}
				AbstractTask task = (AbstractTask) element;
				LocalDate date = LocalDate.now();
				WeekFields weekFields = WeekFields.of(Locale.getDefault());
				// Current day in the week
				int day = date.get(weekFields.dayOfWeek());
				// First date of the week
				LocalDate first = date.minusDays(day - 1);
				int seconds = TimekeeperPlugin.getIntValue(task, first.plusDays(weekday).toString());
				if (seconds > 0) {
					return DurationFormatUtils.formatDuration(seconds * 1000, "H:mm:ss", true);
				}
				return "";
			}
		});
	}

	private class CompositeImageDescriptor {

		ImageDescriptor icon;

		ImageDescriptor overlayKind;

	};

	private class LP extends ColumnLabelProvider {
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

	}

	private void createTitleColumn() {
		TreeViewerColumn keyColumn = createTableViewerColumn("Activity", 400, 1);
		keyColumn.setLabelProvider(new LP());
	}

	private void fillContextMenu(IMenuManager manager) {
		manager.add(action1);
		manager.add(action2);
		// Other plug-ins can contribute there actions here
		manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
	}

	private void fillLocalPullDown(IMenuManager manager) {
		manager.add(action1);
		manager.add(new Separator());
		manager.add(action2);
	}

	private void fillLocalToolBar(IToolBarManager manager) {
		manager.add(action1);
		manager.add(action2);
	}

	private void hookContextMenu() {
		MenuManager menuMgr = new MenuManager("#PopupMenu");
		menuMgr.setRemoveAllWhenShown(true);
		menuMgr.addMenuListener(new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				TimeReportView.this.fillContextMenu(manager);
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
		action1 = new Action() {
			public void run() {
				Collection<AbstractTask> allTasks = TasksUiPlugin.getTaskList().getAllTasks();
				for (AbstractTask abstractTask : allTasks) {
					if (!abstractTask.isActive()) {
						// abstractTask.setAttribute(ACCUMULATED_ID, null);
					}
				}
				viewer.setInput(getViewSite());
			}
		};
		action1.setText("Action 1");
		action1.setToolTipText("Action 1 tooltip");
		action1.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));

		action2 = new Action() {
			public void run() {
				showMessage("Action 2 executed");
			}
		};
		action2.setText("Action 2");
		action2.setToolTipText("Action 2 tooltip");
		action2.setImageDescriptor(PlatformUI.getWorkbench().getSharedImages()
				.getImageDescriptor(ISharedImages.IMG_OBJS_INFO_TSK));
		doubleClickAction = new Action() {
			public void run() {
				ISelection selection = viewer.getSelection();
				Object obj = ((IStructuredSelection) selection).getFirstElement();
				showMessage("Double-click detected on " + obj.toString());
			}
		};
	}

	/**
	 * Passing the focus request to the viewer's control.
	 */
	public void setFocus() {
		viewer.getControl().setFocus();
	}

	private void showMessage(String message) {
		MessageDialog.openInformation(viewer.getControl().getShell(), "Sample View", message);
	}
}