/*******************************************************************************
 * Copyright (c) 2014-2019 Torkild U. Resheim.
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Contributors:
 *    Torkild U. Resheim - initial API and implementation
 *******************************************************************************/
package net.resheim.eclipse.timekeeper.ui;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.util.BundleUtility;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.domain.Activity;
import net.resheim.eclipse.timekeeper.domain.OwnerId;
import net.resheim.eclipse.timekeeper.domain.ActivityId;
import net.resheim.eclipse.timekeeper.service.Commands.StartActivity;
import net.resheim.eclipse.timekeeper.service.Commands.UpdateActivity;
import net.resheim.eclipse.timekeeper.ui.tasks.TaskIntegration;
import net.resheim.eclipse.timekeeper.internal.idle.GenericIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.IdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.MacIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.WindowsIdleTimeDetector;
import net.resheim.eclipse.timekeeper.internal.idle.X11IdleTimeDetector;
import net.resheim.eclipse.timekeeper.service.TimekeeperService;
import net.resheim.eclipse.timekeeper.ui.preferences.PreferenceConstants;

@SuppressWarnings("restriction")
public class TimekeeperUiPlugin extends AbstractUIPlugin implements IPropertyChangeListener {

	public static final String OBJ_ACTIVITY = "OBJ_ACTIVITY";
	public static final String IMG_TOOL_CURRENT = "IMG_TOOL_CURRENT";

	/**
	 * Previous value of the number of milliseconds the system has been considered
	 * idle.
	 */
	private static long lastIdleTimeMillis;

	private static TimekeeperUiPlugin plugin;
	private ServiceTracker<TimekeeperService, TimekeeperService> serviceTracker;
    private net.resheim.eclipse.timekeeper.ui.client.EclipseBackend backend;
	private ServiceTracker<TaskIntegration, TaskIntegration> integrationTracker;
	private Timer timer;
	private ActivityId sampledActivity;

	public static final String PLUGIN_ID = "net.resheim.eclipse.timekeeper.ui"; //$NON-NLS-1$

	/**
	 * Time interval for updating elapsed time on a task (1s).
	 */
	private static final int SHORT_INTERVAL = 1000;

	/**
	 * Embedded-mode policy: calendar reports and editing use the Eclipse host zone.
	 */
	private static final ZoneId CALENDAR_ZONE = ZoneId.systemDefault();

	public static ZoneId getCalendarZone() {
		return plugin!=null && plugin.backend!=null ? plugin.backend.zone() : CALENDAR_ZONE;
	}

	private static String formatInstant(Instant instant) {
		return DateTimeFormatter.ofPattern("EEE e, HH:mm:ss", Locale.US).withZone(getCalendarZone()).format(instant);
	}

	/**
	 * Returns the shared plug-in instance.
	 *
	 * @return the shared instance
	 */
	public static TimekeeperUiPlugin getDefault() {
		return plugin;
	}

	/**
	 * Returns the persistence-neutral application service registered by the
	 * embedded adapter.
	 */
	public TimekeeperService getTimekeeperService() {
        if(backend==null) throw new IllegalStateException("Timekeeper client is not available");
        return backend.service();
	}

    public net.resheim.eclipse.timekeeper.service.WorkSessionService getWorkSessionService() {return backend.workSessions();}
    public java.util.List<Activity> getSessionActivityCandidates() {return backend.sessionActivityCandidates();}
    public boolean isBackendReady() {return backend!=null && backend.ready();}
    public boolean snapshotsAvailable() {return backend!=null && backend.snapshotsAvailable();}
    public boolean isRemote() {return backend!=null && backend.remote();}
    public String backendStatus() {return backend==null ? "Connecting to Timekeeper." : backend.status();}
    public String backendIdentity() {return backend==null ? "connecting" : backend.identity();}
    public void addBackendListener(net.resheim.eclipse.timekeeper.db.DatabaseChangeListener listener) {backend.addListener(listener);}
    public void removeBackendListener(net.resheim.eclipse.timekeeper.db.DatabaseChangeListener listener) {backend.removeListener(listener);}
    public void refreshBackend() {backend.refresh();}
    public int exportRemoteCsv(java.nio.file.Path folder) throws java.io.IOException {return backend.exportCsv(folder);}
    public int importRemoteCsv(java.nio.file.Path folder) throws java.io.IOException {
        return backend.importCsv(folder,getStateLocation().toFile().toPath().resolve("pending-imports"));
    }
    public Activity activateRemoteInWorker(net.resheim.eclipse.timekeeper.domain.TaskId task) {return backend.activateInWorker(task);}
    public void activateRemoteTask(net.resheim.eclipse.timekeeper.domain.TaskId task,Optional<Activity> observed) {backend.activate(task,observed);}
    public <T> org.eclipse.core.runtime.jobs.Job runOperation(String name,java.util.function.Supplier<T> action,
            java.util.function.Consumer<T> success,java.util.function.Consumer<RuntimeException> failure) {
        return backend.execute(name,action,success,failure);
    }
    public <T> org.eclipse.core.runtime.jobs.Job runRead(String name,java.util.function.Supplier<T> action,
            java.util.function.Consumer<T> success,java.util.function.Consumer<RuntimeException> failure) {
        return backend.read(name,action,success,failure);
    }
    public <T> org.eclipse.core.runtime.jobs.Job runBackgroundRead(String name,java.util.function.Supplier<T> action,
            java.util.function.Consumer<T> success,java.util.function.Consumer<RuntimeException> failure) {
        return backend.readBackground(name,action,success,failure);
    }
	public Optional<TaskIntegration> getTaskIntegration() {
		return Optional.ofNullable(integrationTracker == null ? null : integrationTracker.getService());
	}

	private Optional<Activity> activeActivity() {
		if (!isBackendReady())
			return Optional.empty();
		return getTimekeeperService().activeActivity(OwnerId.LOCAL);
	}

	@Override
	protected void initializeImageRegistry(ImageRegistry reg) {
		reg.put(OBJ_ACTIVITY,
				ImageDescriptor.createFromURL(BundleUtility.find(getBundle(), "icons/full/eview/activity_obj.png")));
		reg.put(IMG_TOOL_CURRENT,
				ImageDescriptor.createFromURL(BundleUtility.find(getBundle(), "icons/full/elcl/cur_nav.png")));
	}

	/** Platform specific idle time detector. */
	private IdleTimeDetector detector;

	boolean dialogIsOpen = false;

	// TODO: use system locale?
	private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE d").withLocale(Locale.US);

	private Listener reactivationListener;

	/** The last time any activity was detected. */
	protected Instant lastActiveTime;

	/**
	 * The number of milliseconds before user is considered idle. Controlled by a
	 * preference setting.
	 */
	private static long consideredIdleThreshold;

	/**
	 * The number of milliseconds before user is considered away. Controlled by a
	 * preference setting.
	 */
	private static long afkInterval;

	/**
	 * Stop tracking and deactivate the optional external task when away. Controlled
	 * by a preference setting.
	 */
	private static boolean afkDeactivate;

	public TimekeeperUiPlugin() {
	}

	/**
	 * Returns a list of weekday names and dates, using the default locale to
	 * determine the first day of the week (Sunday or Monday).
	 *
	 * @param date starting date
	 * @return an array with weekday names and dates
	 */
	public String[] getHeadings(LocalDate date) {
		String[] headings = new String[7];
		WeekFields weekFields = WeekFields.of(Locale.getDefault());
		// Current day in the week
		long day = date.get(weekFields.dayOfWeek());
		// First date of the week
		LocalDate first = date.minusDays(day - 1l);
		for (int i = 0; i < 7; i++) {
			headings[i] = formatter.format(first);
			first = first.plusDays(1);
		}
		return headings;
	}

	/**
	 * Must be called by the UI-thread. Handles that the session has been idle and
	 * just reactivated.
	 *
	 * @param idleTimeMillis
	 */
	private void handleReactivation(long idleTimeMillis) {
		// We want only one dialog open.
		if (dialogIsOpen) {
			return;
		}
		synchronized (this) {
			if (idleTimeMillis < lastIdleTimeMillis && lastIdleTimeMillis > consideredIdleThreshold) {
				var active = activeActivity();
				if (active.isEmpty() || lastActiveTime == null || !active.get().id().equals(sampledActivity))
					return;
				Activity activity = active.orElseThrow();
				Instant lastActive = lastActiveTime.isBefore(activity.start()) ? activity.start() : lastActiveTime;
				dialogIsOpen = true;
				try {
					if (lastIdleTimeMillis < afkInterval) {
						String time = DurationFormatUtils.formatDuration(lastIdleTimeMillis, "H:mm:ss", true);
						MessageDialog md = new MessageDialog(Display.getCurrent().getActiveShell(),
								"Disregard idle time?", null,
								MessageFormat.format(
										"The computer has been idle since {0}, more than {1}. Stop current activity and set end time to last active time? A new activity will be started from now.",
										formatInstant(lastActive), time),
								MessageDialog.QUESTION, new String[] { "No", "Yes" }, 1);
						if (md.open() == 1) {
							idleChange(activity,lastActive,true);
						}
					} else {
						idleChange(activity,lastActive,!afkDeactivate);
						if (afkDeactivate) {
							getTaskIntegration().ifPresent(integration -> integration.deactivate(activity.taskId()));
							MessageDialog.openInformation(Display.getCurrent().getActiveShell(),
									"Activity automatically stopped",
									MessageFormat.format(
											"You have been away for too long ({0}) and tracking of the current activity was automatically ended on {1}.",
											DurationFormatUtils.formatDuration(lastIdleTimeMillis, "H:mm:ss", true),
											formatInstant(lastActive)));
                        }
					}
				} catch (net.resheim.eclipse.timekeeper.service.ServiceException failure) {
					MessageDialog.openError(Display.getCurrent().getActiveShell(), "Could not update idle activity",
							failure.getMessage());
				} finally {
					dialogIsOpen = false;
				}

			}
		}
	}

	/**
	 * Returns <code>true</code> if the session is considered idle. Note that if no
	 * task is active, the session will never be considered idle.
	 *
	 * @return <code>true</code> if idle
	 */
	public boolean isIdle() {
		return lastActiveTime != null && activeActivity().isPresent() && lastIdleTimeMillis > consideredIdleThreshold;
	}

	public Instant getActiveSince() {
		return activeActivity().map(Activity::start).orElse(null);
	}

	public Instant getIdleSince() {
		return activeActivity().isPresent() && lastActiveTime != null ? lastActiveTime : null;
	}

    private void idleChange(Activity activity,Instant lastActive,boolean restart) {
        runOperation("Update idle activity",()->{
            closeIdleActivity(activity,lastActive);
            if(restart) getTimekeeperService().startActivity(new StartActivity(activity.taskId(),activity.ownerId(),"",java.util.Set.of()));
            return null;
        },value->{},failure->MessageDialog.openError(Display.getCurrent().getActiveShell(),"Could not update idle activity",failure.getMessage()));
    }
	private void closeIdleActivity(Activity activity, Instant lastActive) {
		getTimekeeperService().updateActivity(new UpdateActivity(activity.id(), activity.version(), activity.start(),
				Optional.of(lastActive), activity.summary(), activity.labelIds()));
	}

	private void installTaxameter() {

		switch (Platform.getOS()) {
		case Platform.OS_MACOSX:
			detector = new MacIdleTimeDetector();
			break;
		case Platform.OS_LINUX:
			detector = new X11IdleTimeDetector();
			break;
		case Platform.OS_WIN32:
			detector = new WindowsIdleTimeDetector();
			break;
		default:
			detector = new GenericIdleTimeDetector();
			break;
		}

		if (detector.getIdleTimeMillis() == IdleTimeDetector.NOT_WORKING) {
			Shell shell = PlatformUI.getWorkbench().getModalDialogShellProvider().getShell();
			MessageDialog.openError(shell, "Idle time detector disabled",
					"The idle time detector could not be configured due to an error "
							+ "and will be disabled for this session. See the error log for details.");
			detector = new GenericIdleTimeDetector();
		}

		final Display display = PlatformUI.getWorkbench().getDisplay();
		timer = new Timer("Timekeeper", true);
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if (!display.isDisposed() && !PlatformUI.getWorkbench().isClosing()) {
					long idleTimeMillis = detector.getIdleTimeMillis();
					try {
						display.asyncExec(() -> {
							if (plugin != TimekeeperUiPlugin.this || PlatformUI.getWorkbench().isClosing())
								return;
							var active = activeActivity();
							if (active.isEmpty()) {
								sampledActivity = null;
								lastActiveTime = null;
								lastIdleTimeMillis = idleTimeMillis;
								return;
							}
							Activity activity = active.orElseThrow();
							if (!activity.id().equals(sampledActivity)) {
								sampledActivity = activity.id();
								lastActiveTime = activity.start();
							} else if (idleTimeMillis < lastIdleTimeMillis
									&& lastIdleTimeMillis > consideredIdleThreshold) {
								handleReactivation(idleTimeMillis);
							} else if (idleTimeMillis < consideredIdleThreshold) {
								lastActiveTime = Instant.now().minusMillis(Math.max(0, idleTimeMillis));
							}
							lastIdleTimeMillis = idleTimeMillis;
						});
					} catch (SWTException shutdown) {
						// Disposal can race the background sample's asyncExec submission.
						if (shutdown.code != SWT.ERROR_DEVICE_DISPOSED)
							throw shutdown;
					}
				}
			}
		}, SHORT_INTERVAL, SHORT_INTERVAL);

		// Immediately run the reactivation handler if the system has been idle
		// and the user has pressed a key or mouse button _inside_ the running
		// application.
		reactivationListener = new Listener() {
			public void handleEvent(Event event) {
				long idleTimeMillis = detector.getIdleTimeMillis();
				if (idleTimeMillis < lastIdleTimeMillis && lastIdleTimeMillis > consideredIdleThreshold) {
					handleReactivation(idleTimeMillis);
				}
				lastIdleTimeMillis = idleTimeMillis;
			}
		};
		display.syncExec(new Runnable() {
			@Override
			public void run() {
				display.addFilter(SWT.KeyUp, reactivationListener);
				display.addFilter(SWT.MouseUp, reactivationListener);
			}
		});
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		serviceTracker = new ServiceTracker<>(context, TimekeeperService.class, null);
		serviceTracker.open();
        net.resheim.eclipse.timekeeper.client.HttpTimekeeperService remote=null;
        String configurationFailure=null;
        if(TimekeeperPlugin.remoteConfigured()) {
            try {
                String token=net.resheim.eclipse.timekeeper.ui.client.BackendCredentials.read();
                remote=new net.resheim.eclipse.timekeeper.client.HttpTimekeeperService(java.net.URI.create(TimekeeperPlugin.backendUrl()),()->token,java.time.Duration.ofSeconds(15));
            } catch(RuntimeException invalidConfiguration) {
                configurationFailure="Check the server URL and token in Timekeeper backend preferences, then restart Eclipse.";
            }
        }
        java.util.function.Supplier<TimekeeperService> embedded=()->{
            var service=serviceTracker.getService();if(service==null) throw new IllegalStateException("Embedded Timekeeper service unavailable");return service;
        };
        backend=configurationFailure==null ? new net.resheim.eclipse.timekeeper.ui.client.EclipseBackend(embedded,remote,PlatformUI.getWorkbench().getDisplay())
                : net.resheim.eclipse.timekeeper.ui.client.EclipseBackend.unavailableRemote(embedded,PlatformUI.getWorkbench().getDisplay(),configurationFailure);
		integrationTracker = new ServiceTracker<>(context, TaskIntegration.class, null);
		integrationTracker.open();
		consideredIdleThreshold = getPreferenceStore().getLong(PreferenceConstants.MINUTES_IDLE) * 60_000l;
		afkInterval = getPreferenceStore().getLong(PreferenceConstants.MINUTES_AWAY) * 60_000l;
		afkDeactivate = getPreferenceStore().getBoolean(PreferenceConstants.DEACTIVATE_WHEN_AWAY);
		getPreferenceStore().addPropertyChangeListener(this);
		installTaxameter();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (timer != null)
			timer.cancel();
		Display display = PlatformUI.getWorkbench().getDisplay();
		if (!display.isDisposed() && reactivationListener != null)
			display.syncExec(() -> {
				display.removeFilter(SWT.KeyUp, reactivationListener);
				display.removeFilter(SWT.MouseUp, reactivationListener);
			});
        if(backend!=null) backend.close();
		if (integrationTracker != null)
			integrationTracker.close();
		if (serviceTracker != null)
			serviceTracker.close();
		serviceTracker = null;
		plugin = null;
		getPreferenceStore().removePropertyChangeListener(this);
		super.stop(context);
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().equals(PreferenceConstants.MINUTES_IDLE)) {
			consideredIdleThreshold = Long.parseLong(event.getNewValue().toString()) * 60_000l;
		}
		if (event.getProperty().equals(PreferenceConstants.MINUTES_AWAY)) {
			afkInterval = Long.parseLong(event.getNewValue().toString()) * 60_000l;
		}
		if (event.getProperty().equals(PreferenceConstants.DEACTIVATE_WHEN_AWAY)) {
			afkDeactivate = Boolean.parseBoolean(event.getNewValue().toString());
		}
	}

}
