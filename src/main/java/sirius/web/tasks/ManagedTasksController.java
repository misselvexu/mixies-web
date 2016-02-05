/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.tasks;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import sirius.kernel.commons.Context;
import sirius.kernel.di.std.Part;
import sirius.kernel.di.std.Register;
import sirius.kernel.nls.NLS;
import sirius.web.controller.BasicController;
import sirius.web.controller.Controller;
import sirius.web.controller.Routed;
import sirius.web.http.WebContext;
import sirius.web.security.LoginRequired;
import sirius.web.security.Permission;
import sirius.web.services.JSONStructuredOutput;
import sirius.web.templates.JavaScriptContentHandler;
import sirius.web.templates.Templates;

import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Created by aha on 30.10.15.
 */
@Register(classes = Controller.class)
public class ManagedTasksController extends BasicController {

    public static final String PERMISSION_SYSTEM_SCRIPTING = "permission-system-scripting";

    @LoginRequired
    @Routed("/system/tasks")
    public void tasks(WebContext ctx) {
        ctx.respondWith().template("view/system/tasks.html");
    }

    @LoginRequired
    @Routed(value = "/system/api/tasks", jsonCall = true)
    public void tasksAPI(WebContext ctx, JSONStructuredOutput json) {
        json.beginArray("tasks");
        for (ManagedTask task : managedTasks.getActiveTasks()) {
            json.beginObject("task");
            json.property("id", task.getId());
            json.property("name", task.getName());
            json.property("state", task.getState().name());
            json.property("user", task.getUsername());
            json.property("started", NLS.toUserString(task.getStarted()));
            json.property("scheduled", NLS.toUserString(task.getScheduled()));
            json.endObject();
        }
        json.endArray();
    }

    @Routed("/system/task/:1")
    @LoginRequired
    public void task(WebContext ctx, String taskId) {

    }

    @Permission(PERMISSION_SYSTEM_SCRIPTING)
    @Routed("/system/scripting")
    public void scripting(WebContext ctx) {
        ctx.respondWith().template("view/system/scripting.html");
    }

    @Part
    private ManagedTasks managedTasks;

    @Part
    private Templates templates;

    @Routed(value = "/system/scripting/api/execute", jsonCall = true)
    @Permission(PERMISSION_SYSTEM_SCRIPTING)
    public void scriptingExecute(WebContext ctx, JSONStructuredOutput json) throws IOException {
        String scriptSource = CharStreams.toString(new InputStreamReader(ctx.getContent(), Charsets.UTF_8));
        ManagedTask mt = managedTasks.createManagedTaskSetup("Custom Script").execute(jobCtx -> {
            Context params = Context.create();
            params.set("task", jobCtx);
            templates.generator()
                     .applyContext(params)
                     .direct(scriptSource, JavaScriptContentHandler.JS)
                     .generateTo(null);
        });

        json.property("success", true);
        json.property("task", mt.getId());
    }

    @Routed(value = "/system/task/:1/api/info", jsonCall = true)
    @LoginRequired
    public void taskInfo(WebContext ctx, JSONStructuredOutput json, String taskId) {
        ManagedTask task = managedTasks.findTask(taskId);

        if (task == null) {
            json.property("found", false);
        } else {
            json.property("found", true);
            json.property("name", task.getName());
            json.property("state", task.getState());
            long logLimit = ctx.get("logLimit").asLong(0);
            json.array("logs", task.getLastLogs(), (o, log) -> {
                if (logLimit == 0 || log.getTod().toEpochMilli() > logLimit) {
                    o.beginObject("entry");
                    o.property("date", NLS.toUserString(log.getTod()));
                    o.property("timestamp", log.getTod().toEpochMilli());
                    o.property("message", log.getMessage());
                    o.property("type", log.getType());
                    o.endObject();
                }
            });
            if (task.getLastLogs().isEmpty()) {
                json.property("lastLog", 0);
            } else {
                json.property("lastLog", task.getLastLogs().get(task.getLastLogs().size() - 1).getTod().toEpochMilli());
            }
        }
    }

    @LoginRequired
    @Routed(value = "/system/task/:1/api/cancel",jsonCall = true)
    public void taskCancel(WebContext ctx, JSONStructuredOutput json, String taskId) {
        ManagedTask task = managedTasks.findTask(taskId);

        if (task != null) {
            task.cancel();
        }
    }
}