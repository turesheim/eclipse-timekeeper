<html>
	<head>
		<style>
			body { font-family: "Helvetica Neue", sans-serif; font-size: 11pt}
			table { border: 1px solid #eee; border-collapse: collapse; }
			.header-row { background: #aaa; border-bottom: 1px solid #eee; color: #fff; }
			.header-th { text-align: center; border-left: 1px solid #eee; }
			.project-row { background: #eee; font-weight: bold }
			.project-td { text-align: right; border-left: 1px solid #eee; }
			.task-row { background: #eee; }
			.activity-td { text-align: right; border-left: 1px solid #eee; }
			.footer-row { background: #aaa; border-top: 1px solid #eee;  color: #fff; font-weight: bold}
			.footer-td { text-align: right; border-left: 1px solid #bcc7c7; }
			.sum-td { background: #aaa; text-align: right; border: 1px solid #eee; color: #fff; }
		</style>
		<meta charset="UTF-8">
	</head>
	<h1>Timesheet</h1>
	<#list weeks as week>
	<!-- create one table for each week -->
	<table width="100%">
		<tr class="header-row">
			<!-- first header column -->
			<th>Week ${formatDateTime(week.firstDayOfWeek, 'w - YYYY')}</th>
			<!-- weekday columns -->
			<#list week.dates as date>
			<th width="50em" class="header-th">${formatDateTime(date, 'EEE d')}</th>
			</#list>
			<th width="50em" class="header-th">Sum</th>
		</tr>
		<!-- group per project -->
		<#list week.projects as project, tasks>
		<tr class="project-row">
			<td>${project}</td>
			<!-- sum per project per day -->
			<#list week.dates as date>							
			<td class="project-td">${formatDuration(week.getSum(project, date))}</td>
			</#list>
			<td class="sum-td">${formatDuration(week.getSum(project))}</td>
		</tr>
		<!-- sub group per task -->
		<#list tasks as task>		
		<tr class="task-row">
			<td><#if task.url??><a href="${task.getUrl}">${task.taskId}</a><#else>${task.taskId}</#if> :${task.summary}</td>
			<#list week.dates as date><td class="project-td">${formatDuration(date, task)}</td></#list>
			<td class="sum-td">${formatDuration(week.getSum(task))}</td>
		</tr>
		<!-- group activities per date -->
		<#list week.dates as date>							
		<#list getActivities(date, task) as activity>
		<tr> 
			<td>-&nbsp;${activity.summary}</td>
			<#list week.dates as date><td class="activity-td">${formatDuration(date, activity)}</td></#list>
			<td class="sum-td">&nbsp;</td>
		</tr>
		</#list>
		</#list>
		</#list> <!-- tasks ->
		</#list> <!-- projects -->
		<!-- footer -->
		<tr class="footer-row">
			<td>Daily total</td>
			<#list week.dates as date>							
			<td class="footer-td">${formatDuration(week.getSum(date))}</td>
			</#list>
			<td class="footer-td">${formatDuration(week.getSum())}</td>
		</tr>
	</table>
	</#list>
</html>
