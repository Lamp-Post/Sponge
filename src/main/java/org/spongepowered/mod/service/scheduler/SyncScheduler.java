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
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.service.scheduler.SynchronousScheduler;
import org.spongepowered.api.service.scheduler.Task;
import org.spongepowered.mod.SpongeMod;

import java.util.Queue;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * <p>Internal implementation of the SynchronousScheduler interface.</p>
 *
 * @see {@link org.spongepowered.api.service.scheduler.SynchronousScheduler}
 */
public class SyncScheduler implements SynchronousScheduler {

    // The simple queue of all pending (and running) ScheduledTasks
    final Queue<ScheduledTask> taskList = new ConcurrentLinkedQueue<ScheduledTask>();
    // The internal counter of the number of Ticks elapsed since this Scheduler was listening for
    // ServerTickEvent from Forge.
    volatile long counter = 0L;

    /**
     * <p>
     * We establish the SyncScheduler when the SyncScheduler is created.  This will
     * happen once.  We simply initialize the first state in the SyncScheduler state
     * machine and let the state machine execute.  Only calls to the SyncScheduler
     * through the control interface (TBD) may control the behavior of the state machine itself.</p>
     * <p/>
     * <p>
     * The constructor of the Scheduler is private.  So to get the scheduler, user code calls game.getScheduler()
     * or directly by SyncScheduler.getInstance().  In time access to the scheduler should be migrated into
     * the Services Manager.</p>
     */
    private SyncScheduler() {
    }

    /**
     * <p>Returns the instance (handle) to the Synchronous TaskScheduler.</p>
     * <p/>
     * <p>
     * A static reference to the Synchronous Scheduler singleton is returned by
     * the function getInstance().  The implementation of getInstance follows the usage
     * of the AtomicReference idiom.</p>
     *
     * @return The single interface to the Synchronous Scheduler
     */
     private static class SynchronousSchedulerSingletonHolder {

        private static final SynchronousScheduler INSTANCE = new SyncScheduler();
     }

     public static SynchronousScheduler getInstance() {
         return SynchronousSchedulerSingletonHolder.INSTANCE;
     }

    /**
     * <p>The hook to update the Ticks known by the SyncScheduler.</p>
     * <p/>
     * <p>
     * When a TickEvent occurs, the event handler onTick will accumulate a new value for
     * the counter.  The Phase of the TickEvent used is the TickEvent.ServerTickEvent Phase.START.</p>
     * <p/>
     * <p>
     * The counter is equivalent to a clock in that each new value represents a new
     * tick event.  Use of delay (Task.offset), interval (Task.period), timestamp (Task.timestamp) all
     * are based on the time unit of Ticks.  To make it easier to work with in in Plugins, the type
     * is simply a @long but it's never negative.  A better representation would been Number (a cardinal
     * value), but this is what we're using.</p>
     *
     * @param event The Forge ServerTickEvent observed by this object.
     */
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            this.counter++;
            processTasks();
        }
    }

    void processTasks() {
        //
        // For each task, inspect the state.
        //
        // For the state of CANCELED, remove it and look at the next task, if any.
        //
        // For the state of WAITING, the task has not begun, so check the
        // offset time before starting that task for the first time.
        //
        // Else if the task is already RUNNING, use the period (the time delay until
        // the next moment to run the task).
        //
        for (ScheduledTask task : this.taskList) {
            // If the task is now slated to be canceled, we just remove it as if it no longer exists.
            if (task.state == ScheduledTask.ScheduledTaskState.CANCELED) {
                this.taskList.remove(task);
                continue;
            }

            long threshold = Long.MAX_VALUE;

            // Figure out if we start a delayed Task after threshold ticks or,
            // start it after the interval (period) of the repeating task parameter.
            if (task.state == ScheduledTask.ScheduledTaskState.WAITING) {
                threshold = task.offset;
            } else if (task.state == ScheduledTask.ScheduledTaskState.RUNNING) {
                threshold = task.period;
            }

            // This moment is 'now'
            long now = this.counter;

            // So, if the current time minus the timestamp of the task is greater than
            // the delay to wait before starting the task, then start the task.
            // Repeating tasks get a reset-timestamp each time they are set RUNNING
            // If the task has a period of 0 (zero) this task will not repeat, and is removed
            // after we start it.
            if (threshold <= (now - task.timestamp)) {
                // startTask is just a utility function within the Scheduler that
                // starts the task.
                // If the task is not a one time shot then keep it and
                // change the timestamp to now.  It is a little more
                // efficient to do this now than after starting the task.
                task.timestamp = this.counter;
                boolean bTaskStarted = startTask(task);
                if (bTaskStarted) {
                    task.setState(ScheduledTask.ScheduledTaskState.RUNNING);
                    // If task is one time shot, remove it from the list.
                    if (task.period == 0L) {
                        this.taskList.remove(task);
                    }
                }
            }
        }
    }

    Optional<Task> utilityForAddingTask(ScheduledTask task) {
        Optional<Task> result = Optional.absent();
        this.taskList.add(task);
        result = Optional.of((Task) task);
        return result;
    }

    /**
     * <p>Runs a Task once immediately.</p>
     *
     * <p>
     * The runTask method is used to run a single Task just once.  The Task
     * may persist for the life of the server, however the Task itself will never
     * be restarted.  It has no delay offset.  The Scheduler will not wait before
     * running the Task.</p>
     *
     * <p>Example code to obtain plugin container argument from User code:</p>
     *
     * <p>
     * <code>
     *     Optional&lt;PluginContainer&gt; result;
     *     result = evt.getGame().getPluginManager().getPlugin("YOUR_PLUGIN");
     *     PluginContainer pluginContainer = result.get();
     * </code>
     * </p>
     *
     * @param plugin The plugin container of the Plugin that initiated the Task
     * @param runnableTarget  The Runnable object that implements a run() method to execute the Task desired
     * @return Optional&lt;Task&gt;&nbsp; Either Optional.absent() if invalid or a reference to the new Task
     */
    @Override
    public Optional<Task> runTask(Object plugin, Runnable runnableTarget) {
        /**
         * <p>
         * The intent of this method is to run a single task (non-repeating) and has zero
         * offset (doesn't wait a delay before starting), and a zero period (no repetition)</p>
         */
        Optional<Task> result = Optional.absent();
        final long NODELAY = 0L;
        final long NOPERIOD = 0L;

        ScheduledTask nonRepeatingTask = taskValidationStep(plugin, runnableTarget, NODELAY, NOPERIOD);

        if (nonRepeatingTask == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.CANNOT_MAKE_TASK_WARNING);
        } else {
            result = utilityForAddingTask(nonRepeatingTask);
        }

        return result;
    }

    /**
     * <p>Runs a Task once after a specific delay offset.</p>
     *
     * <p>
     * The runTask method is used to run a single Task just once.  The Task
     * may persist for the life of the server, however the Task itself will never
     * be restarted.  The Scheduler will not wait before running the Task.
     * <b>The Task will be delayed artificially for delay Ticks.</b>  Because the time
     * unit is in Ticks, this scheduled Task is synchronous (as possible) with the
     * event of the Tick from the game.  Overhead, network and system latency not 
     * withstanding the event will fire after the delay expires.</p>
     *
     * <p>Example code to obtain plugin container argument from User code:</p>
     *
     * <p>
     * <code>
     *     Optional&lt;PluginContainer&gt; result;
     *     result = evt.getGame().getPluginManager().getPlugin("YOUR_PLUGIN");
     *     PluginContainer pluginContainer = result.get();
     * </code>
     * </p>
     *
     * @param plugin The plugin container of the Plugin that initiated the Task
     * @param runnableTarget  The Runnable object that implements a run() method to execute the Task desired
     * @param delay  The offset in ticks before running the task.
     * @return Optional&lt;Task&gt; Either Optional.absent() if invalid or a reference to the new Task
     */
    @Override
    public Optional<Task> runTaskAfter(Object plugin, Runnable runnableTarget, long delay) {
        Optional<Task> result = Optional.absent();
        final long NOPERIOD = 0L;

        ScheduledTask nonRepeatingTask = taskValidationStep(plugin, runnableTarget, delay, NOPERIOD);

        if (nonRepeatingTask == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.CANNOT_MAKE_TASK_WARNING);
        } else {
            result = utilityForAddingTask(nonRepeatingTask);
        }

        return result;
    }

    /**
     * <p>Start a repeating Task with a period (interval) of Ticks.  The first occurrence will start immediately.</p>
     *
     * <p>
     * The runRepeatingTask method is used to run a Task that repeats.  The Task
     * may persist for the life of the server. The Scheduler will not wait before running
     * the first occurrence of the Task. The Scheduler will not allow a second occurrence of
     * the task to start if the preceding occurrence is is still alive.  Be sure to end
     * the Runnable Thread of the Task before anticipating the recurrence of the Task.</p>
     *
     * <p> 
     * If the Scheduler detects that two tasks will overlap as such, the 2nd Task will not
     * be started.  The next time the Task is due to run, the test will be made again to determine
     * if the previous occurrence of the Task is still alive (running).  As long as a previous occurrence
     * is running no new occurrences of that specific Task will start, although the Scheduler will
     * never cease in trying to start it a 2nd time.</p>
     * 
     * <p>
     * Because the time unit is in Ticks, this scheduled Task is synchronous (as possible) with the
     * event of the Tick from the game.  Overhead, network and system latency not 
     * withstanding the Task will run (and re-run) after the delay expires.</p>
     *
     * <p>Example code to obtain plugin container argument from User code:</p>
     *
     * <p>
     * <code>
     *     Optional&lt;PluginContainer&gt; result;
     *     result = evt.getGame().getPluginManager().getPlugin("YOUR_PLUGIN");
     *     PluginContainer pluginContainer = result.get();
     * </code>
     * </p>
     *
     * @param plugin The plugin container of the Plugin that initiated the Task
     * @param runnableTarget  The Runnable object that implements a run() method to execute the Task desired
     * @param interval The period in ticks of the repeating Task.
     * @return Optional&lt;Task&gt; Either Optional.absent() if invalid or a reference to the new Task
     */
    @Override
    public Optional<Task> runRepeatingTask(Object plugin, Runnable runnableTarget, long interval) {
        Optional<Task> result = Optional.absent();
        final long NODELAY = 0L;

        ScheduledTask repeatingTask = taskValidationStep(plugin, runnableTarget, NODELAY, interval);

        if (repeatingTask == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.CANNOT_MAKE_TASK_WARNING);
        } else {
            result = utilityForAddingTask(repeatingTask);
        }

        return result;
    }

    /**
     * <p>
     * Start a repeating Task with a period (interval) of Ticks.  
     * The first occurrence will start after an initial delay.</p>
     *
     * <p>
     * The runRepeatingTask method is used to run a Task that repeats.  The Task
     * may persist for the life of the server. The Scheduler <b>will wait</b> before running
     * the first occurrence of the Task. The Scheduler will not allow a second occurrence of
     * the task to start if the preceding occurrence is is still alive.  Be sure to end
     * the Runnable Thread of the Task before anticipating the recurrence of the Task.</p>
     *
     * <p> 
     * If the Scheduler detects that two tasks will overlap as such, the 2nd Task will not
     * be started.  The next time the Task is due to run, the test will be made again to determine
     * if the previous occurrence of the Task is still alive (running).  As long as a previous occurrence
     * is running no new occurrences of that specific Task will start, although the Scheduler will
     * never cease in trying to start it a 2nd time.</p>
     * 
     * <p>
     * Because the time unit is in Ticks, this scheduled Task is synchronous (as possible) with the
     * event of the Tick from the game.  Overhead, network and system latency not 
     * withstanding the Task will run (and re-run) after the delay expires.</p>
     *
     * <p>Example code to obtain plugin container argument from User code:</p>
     *
     * <p>
     * <code>
     *     Optional&lt;PluginContainer&gt; result;
     *     result = evt.getGame().getPluginManager().getPlugin("YOUR_PLUGIN");
     *     PluginContainer pluginContainer = result.get();
     * </code>
     * </p>
     *
     * @param plugin The plugin container of the Plugin that initiated the Task
     * @param runnableTarget  The Runnable object that implements a run() method to execute the Task desired
     * @param delay  The offset in ticks before running the task.
     * @param interval The offset in ticks before running the task.
     * @return Optional&lt;Task&gt; Either Optional.absent() if invalid or a reference to the new Task
     */
    @Override
    public Optional<Task> runRepeatingTaskAfter(Object plugin, Runnable runnableTarget, long interval, long delay) {
        Optional<Task> result = Optional.absent();

        ScheduledTask repeatingTask = taskValidationStep(plugin, runnableTarget, delay, interval);

        if (repeatingTask == null) {
            SpongeMod.instance.getLogger().warn(SchedulerLogMessages.CANNOT_MAKE_TASK_WARNING);
        } else {
            result = utilityForAddingTask(repeatingTask);
        }

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
    @Override
    public Optional<Task> getTaskById(UUID id) {
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
    @Override
    public Collection<Task> getScheduledTasks() {
        Collection<Task> taskCollection;
        synchronized(this.taskList) {
            taskCollection = new ArrayList<Task>(this.taskList);
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
    @Override
    public Collection<Task> getScheduledTasks(Object plugin) {

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

        synchronized(this.taskList) {
            subsetCollection = new ArrayList<Task>(this.taskList);
        }

        Iterator<Task> it = subsetCollection.iterator();

        while (it.hasNext()) {
            String pluginId = ((PluginContainer) it.next()).getId();
            if (!testOwnerID.equals(pluginId)) it.remove();
        }

        return subsetCollection;
    }

    // offset and period are in milliseconds (unless the Synchronicity of the Task is SYNCHRONOUS)
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

        boolean ASYNCHRONOUS = false;
        return new ScheduledTask(offset, period, ASYNCHRONOUS)
                .setTimestamp(counter)
                .setPluginContainer(plugincontainer)
                .setRunnableBody(runnableTarget);
    }

    boolean startTask(ScheduledTask task) {
        // We'll succeed unless there's an exception found when we try to start the
        // actual Runnable target.
        boolean bRes = true;

        Runnable taskRunnableBody = task.runnableBody;
        try {
            taskRunnableBody.run();
        } catch (Exception ex) {
            SpongeMod.instance.getLogger().error(SchedulerLogMessages.USER_TASK_FAILED_TO_RUN_ERROR);
            SpongeMod.instance.getLogger().error(ex.toString());
            bRes = false;

        }
        return bRes;
    }
}
