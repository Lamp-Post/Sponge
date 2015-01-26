/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered.org <http://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.mod.service.scheduler;

import com.google.common.base.Optional;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.service.scheduler.Task;
import org.spongepowered.mod.SpongeMod;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SchedulerHelper {

    long sequenceNumber = 0L;
    Task.TaskSynchroncity syncType;

    private SchedulerHelper() {}

    public SchedulerHelper(Task.TaskSynchroncity syncType) {
        this.syncType = syncType;
    }

    Optional<Task> utilityForAddingTask(Queue<ScheduledTask> taskList, ScheduledTask task) {
        Optional<Task> result = Optional.absent();
        taskList.add(task);
        result = Optional.of((Task) task);
        return result;
    }

    /**
     * <p>
     * Start a repeating Task with a period (interval) of Ticks.  The first occurrence
     * will start after an initial delay.</p>
     *
     * <p>Example code to use the method:</p>
     *
     * <p>
     * <code>
     *     UUID myID;
     *     // ...
     *     Optional&lt;Task&gt; task;
     *     task = SyncScheduler.getInstance().getTaskById(myID);
     * </code>
     * </p>
     *
     * @param id The UUID of the Task to find.
     * @return Optional&lt;Task&gt; Either Optional.absent() if invalid or a reference to the existing Task.
     */
    public Optional<Task> getTaskById(Queue<ScheduledTask> taskList, UUID id) {
        Optional<Task> result = Optional.absent();
        for (ScheduledTask t : taskList) {
            if ( id.equals ( t.id) ) {
                return Optional.of ( (Task) t);
            }
        }
        return result;
    }

    /**
     * <p>Determine the list of Tasks that the TaskScheduler is aware of.</p>
     *
     * @return Collection&lt;Task&gt; of all known Tasks in the TaskScheduler
     */
    public Collection<Task> getScheduledTasks(Queue<ScheduledTask> taskList) {
        Collection<Task> taskCollection;
        synchronized(taskList) {
            taskCollection = new ArrayList<Task>(taskList);
        }
        return taskCollection;
    }

    /**
     * <p>The query for Tasks owned by a target Plugin owner is found by testing
     * the list of Tasks by testing the ID of each PluginContainer.</p>
     *
     * <p>If the PluginContainer passed to the method is not correct (invalid
     * or null) then return a null reference.  Else, return a Collection of Tasks
     * that are owned by the Plugin.</p>
     * @param plugin The plugin that may own the Tasks in the TaskScheduler
     * @return Collection&lt;Task&gt; of Tasks owned by the PluginContainer plugin.
     */
    public Collection<Task> getScheduledTasks(Queue<ScheduledTask> taskList, Object plugin) {

        // The argument is an Object so we have due diligence to perform...
        // Owner is not a PluginContainer derived class
        if (!PluginContainer.class.isAssignableFrom(plugin.getClass())) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.PLUGIN_CONTAINER_INVALID_WARNING);
            // The plugin owner was not valid, so the "Collection" is empty.
            //(TODO) Perhaps we move this into using Optional<T> to make it explicit that
            // Eg., the resulting Collection is NOT present vs. empty.
            return null;
        }

        // The plugin owner is OK, so let's figure out which Tasks (if any) belong to it.
        // The result Collection represents the Tasks that are owned by the plugin.  The list
        // is non-null.  If no Tasks exists owned by the Plugin, return an empty Collection
        // else return a Collection of Tasks.

        PluginContainer testedOwner = (PluginContainer) plugin;
        String testOwnerID = testedOwner.getId();
        Collection<Task> subsetCollection;

        synchronized(taskList) {
            subsetCollection = new ArrayList<Task>(taskList);
        }

        Iterator<Task> it = subsetCollection.iterator();

        while (it.hasNext()) {
            String pluginId = ((PluginContainer) it.next()).getId();
            if (!testOwnerID.equals(pluginId)) it.remove();
        }

        return subsetCollection;
    }

    /**
     * <p>Get the UUID of the task by name.</p>
     * @param name  The name of the task to search
     * @return  The Optional&lt;UUID&gt; result from the search by name.
     */
    Optional<UUID> getUuidOfTaskByName(Queue<ScheduledTask> taskList, String name) {
        Optional<UUID> result = Optional.absent();
        for (ScheduledTask t : taskList) {
            if ( name.equals ( t.name) ) {
                return Optional.of ( (UUID) t.id);
            }
        }
        return result;

    }

    /**
     * <p>Get a collection of UUIDs for tasks that match the Regular Expression
     * pattern</p>
     *
     * <p>If no tasks match the pattern, the collection is Optional.absent()</p>
     * <p>If there are Tasks that match the regular expression pattern, the
     * Collection is not Optional.absent().</p>
     *
     * @param pattern The regular expression pattern applied to the name of tasks.
     * @return An Optional&lt;UUID&gt; result of task UUIDs that match the pattern or Optional.absent() if none match.
     */
    Collection<Task> getfTasksByName(Queue<ScheduledTask> taskList, String pattern) {

        Collection<Task> subsetCollection;
        Pattern searchPattern = Pattern.compile(pattern);

        synchronized(taskList) {
            subsetCollection = new ArrayList<Task>(taskList);
        }

        Iterator<Task> it = subsetCollection.iterator();

        while (it.hasNext()) {
            String taskName = ((PluginContainer) it.next()).getName();
            Matcher matcher = searchPattern.matcher(taskName);
            if ( ! matcher.matches()) {
                it.remove();
            }
        }

        return subsetCollection;
    }

    ScheduledTask taskValidationStep(Object plugin, Runnable runnableTarget, long offset, long period) {

        // No owner
        if (plugin == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.PLUGIN_CONTAINER_NULL_WARNING);
            return null;
        }  else if (!PluginContainer.class.isAssignableFrom(plugin.getClass())) {
            // Owner is not a PluginContainer derived class
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.PLUGIN_CONTAINER_INVALID_WARNING);
            return null;
        }

        // The default name of the task is derived from the Plugin ID that owns the Task
        // and a sequence number.
        String pluginId = ((PluginContainer) plugin).getId();
        String taskName;

        sequenceNumber++;

        if (pluginId == null) {
            taskName = String.format("Unknown-%d", sequenceNumber);
        } else {
            taskName = String.format("%s-S%d", pluginId, sequenceNumber);
        }

        // Is task a Runnable task?
        if (runnableTarget == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.NULL_RUNNABLE_ARGUMENT_WARNING);
            return null;
        }

        if ( offset < 0L ) {
            SpongeMod.instance.getLogger().error(SchedulerLogMessages.DELAY_NEGATIVE_ERROR);
            return null;
        }

        if ( period < 0L ) {
            SpongeMod.instance.getLogger().error(SchedulerLogMessages.INTERVAL_NEGATIVE_ERROR);
            return null;
        }

        // plugin is a PluginContainer
        PluginContainer plugincontainer = (PluginContainer) plugin;

        // The caller provided a valid PluginContainer owner and a valid Runnable task.
        // Convert the arguments and store the Task for execution the next time the task
        // list is checked. (this task is firing immediately)
        // A task has at least three things to keep track:
        //   The container that owns the task (pcont)
        //   The Thread Body (Runnable) of the Task (task)
        //   The Task Period (the time between firing the task.)   A default TaskTiming is zero (0) which
        //    implies a One Time Shot (See Task interface).  Non zero Period means just that -- the time
        //    in milliseconds between firing the event.   The "Period" argument to making a new
        //    ScheduledTask is a Period interface intentionally so that

        ScheduledTask tmpTask = new ScheduledTask(offset, period, syncType)
                .setPluginContainer(plugincontainer)
                .setRunnableBody(runnableTarget);

        tmpTask.setName(taskName);

        return tmpTask;
    }

}
