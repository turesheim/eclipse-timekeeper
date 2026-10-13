package net.resheim.eclipse.timekeeper.ui.test;

import static org.junit.Assert.*;

import java.util.UUID;
import org.eclipse.mylyn.internal.tasks.core.AbstractTask;
import org.eclipse.mylyn.internal.tasks.core.RepositoryQuery;
import org.eclipse.mylyn.internal.tasks.core.TaskCategory;
import org.eclipse.mylyn.internal.tasks.core.TaskList;
import org.eclipse.mylyn.internal.tasks.core.UnmatchedTaskContainer;
import org.junit.Test;
import net.resheim.eclipse.timekeeper.mylyn.MylynMetadata;

/** Actual Task List membership, including queries and nested provider tasks. */
@SuppressWarnings("restriction")
public class MylynQueryProjectTest {
    private static final String REPOSITORY="https://github.com/example/tasks";
    private static final class GithubTask extends AbstractTask {
        GithubTask(String id) {super(REPOSITORY,id,"Issue "+id);}
        @Override public boolean isLocal() {return false;}
        @Override public String getConnectorKind() {return "github";}
    }
    private static RepositoryQuery query(TaskList list,String name) {
        var query=new RepositoryQuery("github",UUID.randomUUID().toString());query.setSummary(name);
        query.setRepositoryUrl(REPOSITORY);list.addQuery(query);return query;
    }
    @Test public void queriesSeparateProjectsWithinOneRepositoryAndPropagateToSubtasks() {
        var list=new TaskList();list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
        var first=new GithubTask("1");var second=new GithubTask("2");var child=new GithubTask("3");
        list.addTask(first,query(list,"Timekeeper"));list.addTask(second,query(list,"Other project"));
        list.addTask(child,first);
        assertEquals("Timekeeper",MylynMetadata.getMylynProjectName(first,null,null));
        assertEquals("Other project",MylynMetadata.getMylynProjectName(second,null,null));
        assertEquals("Timekeeper",MylynMetadata.getMylynProjectName(child,null,null));
    }
    @Test public void categoriesTakePriorityAndMultipleQueriesHaveStableFallback() {
        var list=new TaskList();list.addUnmatchedContainer(new UnmatchedTaskContainer("github",REPOSITORY));
        var task=new GithubTask("1");list.addTask(task);
        assertEquals("Uncategorized",MylynMetadata.getMylynProjectName(task,null,null));
        list.addTask(task,query(list,"Zulu"));list.addTask(task,query(list,"Alpha"));
        assertEquals("Alpha",MylynMetadata.getMylynProjectName(task,null,null));
        var category=new TaskCategory("explicit-category","Chosen project");list.addCategory(category);list.addTask(task,category);
        assertEquals("Chosen project",MylynMetadata.getMylynProjectName(task,null,null));
    }
}
