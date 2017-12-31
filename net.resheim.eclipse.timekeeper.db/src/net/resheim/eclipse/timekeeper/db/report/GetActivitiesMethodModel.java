package net.resheim.eclipse.timekeeper.db.report;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.eclipse.mylyn.tasks.core.ITask;

import freemarker.ext.beans.StringModel;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import net.resheim.eclipse.timekeeper.db.Activity;
import net.resheim.eclipse.timekeeper.db.TimekeeperPlugin;
import net.resheim.eclipse.timekeeper.db.TrackedTask;

/**
 * FreeMarker template model for getting retrieving activities from a task.
 * 
 * @author Torkild Ulvøy Resheim
 */
public class GetActivitiesMethodModel implements TemplateMethodModelEx {

	private boolean hasData(Activity activity, LocalDate date) {
		LocalDate endDate = date.plusDays(1);
		return activity.getDuration(date, endDate) != Duration.ZERO;
	}

	public Object exec(@SuppressWarnings("rawtypes") List args) throws TemplateModelException {
		if (args.size() != 2) {
			throw new TemplateModelException("Wrong number of arguments, was expecting (LocalDate, ITask)");
		}
		LocalDate date = (LocalDate) ((StringModel) args.get(0)).getWrappedObject();
		ITask task = (ITask) ((StringModel) args.get(1)).getWrappedObject();
		TrackedTask ttask = TimekeeperPlugin.getDefault().getTask((ITask) task);
		return ttask.getActivities()
				.stream()
				.filter(a -> hasData(a, date))
				.toArray();
	}
}