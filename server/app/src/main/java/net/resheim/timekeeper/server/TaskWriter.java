package net.resheim.timekeeper.server;

import com.fasterxml.jackson.databind.JsonNode;

/** Mutations run outside owner transactions, after the durable SENDING record is committed. */
interface TaskWriter {
    TaskCloudProvider.RemoteTask get(String token,String list,String task);
    TaskCloudProvider.RemoteTask create(String token,String list,JsonNode fields);
    default TaskCloudProvider.RemoteTask create(String token,String list,JsonNode fields,String parent,String previous){return create(token,list,fields);}
    TaskCloudProvider.RemoteTask update(String token,String list,String task,String revision,JsonNode fields);
    void delete(String token,String list,String task,String revision);
    default TaskCloudProvider.RemoteTask move(String token,String list,String task,String revision,String destination,String parent,String previous){throw new TaskProviderHttp.Failure("TASK_PROVIDER_MOVE_UNSUPPORTED",0,0,false);}
}
