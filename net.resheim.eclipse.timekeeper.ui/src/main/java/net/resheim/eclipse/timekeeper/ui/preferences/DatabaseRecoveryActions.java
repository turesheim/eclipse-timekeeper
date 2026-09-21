package net.resheim.eclipse.timekeeper.ui.preferences;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import net.resheim.eclipse.timekeeper.db.DatabaseRecovery;

/** Explicit recovery actions, also available when the configured database failed to open. */
final class DatabaseRecoveryActions {
	private DatabaseRecoveryActions() { }

	static void addTo(Composite parent) {
		Group group = new Group(parent, SWT.NONE);
		group.setText("Historical database recovery");
		group.setLayout(new GridLayout(2, false));
		group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		Label description = new Label(group, SWT.WRAP);
		description.setText("Convert a trusted H2 1.4.194 backup ZIP into a separate database.\n"
				+ "The original database and your storage preferences are not changed.");
		description.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
		Button convert = new Button(group, SWT.PUSH);
		convert.setText("Convert historical backup...");
		convert.addListener(SWT.Selection, event -> convert(group.getShell()));
		Button verify = new Button(group, SWT.PUSH);
		verify.setText("Verify recovered database...");
		verify.addListener(SWT.Selection, event -> verify(group.getShell()));
	}

	private static void convert(Shell shell) {
		FileDialog input = new FileDialog(shell, SWT.OPEN);
		input.setText("Choose a trusted historical H2 backup ZIP");
		input.setFilterExtensions(new String[] { "*.zip" });
		String backup = input.open();
		if (backup == null) return;
		DirectoryDialog output = new DirectoryDialog(shell);
		output.setText("Choose a parent folder for a new recovery directory");
		String parent = output.open();
		if (parent == null) return;
		Path destination = Path.of(parent).resolve("timekeeper-recovery-" + UUID.randomUUID());
		if (!MessageDialog.openConfirm(shell, "Convert historical backup",
				"Use a backup created with H2 1.4.194, containing exactly one .mv.db file.\n"
				+ "Do not ZIP or copy a database while it is in use. Stop time tracking before your final backup.\n\n"
				+ "The backup, extracted source, converted database and validation receipt will be retained in:\n"
				+ destination + "\n\nOnly open trusted backups. Continue?")) return;
		run(shell, destination, true, monitor -> DatabaseRecovery.recover(Path.of(backup), destination, monitor::isCanceled));
	}

	private static void verify(Shell shell) {
		DirectoryDialog input = new DirectoryDialog(shell);
		input.setText("Choose a completed recovery directory (before starting time tracking)");
		String directory = input.open();
		if (directory == null) return;
		Path destination = Path.of(directory);
		run(shell, destination, false, monitor -> DatabaseRecovery.verify(destination));
	}

	@FunctionalInterface
	private interface Operation {
		DatabaseRecovery.Result run(IProgressMonitor monitor) throws IOException, SQLException;
	}

	private static void run(Shell shell, Path directory, boolean cancellable, Operation operation) {
		AtomicReference<DatabaseRecovery.Result> completed = new AtomicReference<>();
		try {
			new ProgressMonitorDialog(shell).run(true, cancellable, monitor -> {
				monitor.beginTask("Validate historical Timekeeper recovery", IProgressMonitor.UNKNOWN);
				try {
					completed.set(operation.run(monitor));
				} catch (IOException | SQLException | RuntimeException failure) {
					throw new InvocationTargetException(failure);
				} finally {
					monitor.done();
				}
			});
			if (shell.isDisposed()) return;
			var result = completed.get();
			var data = result.data();
			MessageDialog.openInformation(shell, "Recovered database validated",
					"Projects: " + data.projects() + "; tasks: " + data.tasks() + "; activities: " + data.activities()
					+ "\nClosed duration: " + data.closedDuration() + "; open activities: " + data.openActivities()
					+ "\n\nReceipt and files: " + result.directory()
					+ "\n\nJDBC URL (also recorded in validated.properties):\n" + result.jdbcUrl()
					+ "\n\nPreferences have not changed. Keep the original and backup."
					+ (data.openActivities() > 0
							? " Open activities were preserved. Do not switch until interrupted-activity recovery has been reviewed."
							: " Review the totals before manually selecting the new URL and restarting Eclipse.")
					+ " Do not run old and new clients against the same database."
					+ " Rollback means returning to the original database with the old plugin; later records are not merged.");
		} catch (InvocationTargetException | InterruptedException failure) {
			if (shell.isDisposed()) return;
			Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
			MessageDialog.openError(shell, "Recovery did not complete",
					cause.getMessage() + "\n\nPreferences have not changed. Do not select an unvalidated database."
					+ " Any files in the following folder are retained for diagnosis:\n" + directory
					+ "\nRetry conversion from a known-good backup into a new folder."
					+ " Verification receipts apply only before the recovered database is used for time tracking.");
		}
	}
}
