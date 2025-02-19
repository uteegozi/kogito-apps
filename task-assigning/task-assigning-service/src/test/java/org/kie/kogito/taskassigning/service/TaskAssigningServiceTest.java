/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kie.kogito.taskassigning.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.event.Event;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.kogito.taskassigning.ClientServices;
import org.kie.kogito.taskassigning.config.OidcClientLookup;
import org.kie.kogito.taskassigning.core.model.Task;
import org.kie.kogito.taskassigning.core.model.TaskAssigningSolution;
import org.kie.kogito.taskassigning.core.model.TaskAssignment;
import org.kie.kogito.taskassigning.core.model.User;
import org.kie.kogito.taskassigning.core.model.solver.realtime.AddTaskProblemFactChange;
import org.kie.kogito.taskassigning.core.model.solver.realtime.AddUserProblemFactChange;
import org.kie.kogito.taskassigning.core.model.solver.realtime.AssignTaskProblemFactChange;
import org.kie.kogito.taskassigning.core.model.solver.realtime.RemoveTaskProblemFactChange;
import org.kie.kogito.taskassigning.service.config.TaskAssigningConfig;
import org.kie.kogito.taskassigning.service.event.BufferedTaskAssigningServiceEventConsumer;
import org.kie.kogito.taskassigning.service.event.DataEvent;
import org.kie.kogito.taskassigning.service.event.SolutionUpdatedOnBackgroundDataEvent;
import org.kie.kogito.taskassigning.service.event.TaskDataEvent;
import org.kie.kogito.taskassigning.service.event.UserDataEvent;
import org.kie.kogito.taskassigning.service.messaging.ReactiveMessagingEventConsumer;
import org.kie.kogito.taskassigning.service.processing.AttributesProcessorRegistry;
import org.kie.kogito.taskassigning.user.service.UserServiceConnector;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.optaplanner.core.api.score.buildin.bendablelong.BendableLongScore;
import org.optaplanner.core.api.solver.ProblemFactChange;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.core.api.solver.event.SolverEventListener;

import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.kie.kogito.taskassigning.service.TestUtil.mockTaskAssignment;
import static org.kie.kogito.taskassigning.service.TestUtil.mockTaskData;
import static org.kie.kogito.taskassigning.service.TestUtil.mockUser;
import static org.kie.kogito.taskassigning.service.TestUtil.parseZonedDateTime;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskAssigningServiceTest {

    private static final String DATA_INDEX_SERVER_URL = "http://localhost:8180/graphql";

    private static final String USER_1_ID = "USER_1_ID";
    private static final String USER_2_ID = "USER_2_ID";
    private static final String USER_3_ID = "USER_3_ID";

    private static final String TASK_1_ID = "TASK_1_ID";
    private static final ZonedDateTime TASK_1_LAST_UPDATE = parseZonedDateTime("2021-03-11T10:00:00.001Z");
    private static final String TASK_2_ID = "TASK_2_ID";
    private static final ZonedDateTime TASK_2_LAST_UPDATE = parseZonedDateTime("2021-03-11T11:00:00.001Z");
    private static final String TASK_3_ID = "TASK_3_ID";
    private static final ZonedDateTime TASK_3_LAST_UPDATE = parseZonedDateTime("2021-03-11T12:00:00.001Z");
    private static final String TASK_4_ID = "TASK_4_ID";
    private static final ZonedDateTime TASK_4_LAST_UPDATE = parseZonedDateTime("2021-03-11T13:00:00.001Z");

    private static final ZonedDateTime USER_DATA_EVENT_1_TIME = parseZonedDateTime("2021-03-11T15:00:00.001Z");
    private static final ZonedDateTime USER_DATA_EVENT_2_TIME = parseZonedDateTime("2021-03-11T15:00:00.002Z");

    private static final int DATA_LOADER_PAGE_SIZE = 10;
    private static final int PUBLISH_WINDOW_SIZE = 5;

    private static final long TIMER_ID = 1;
    private static final Duration IMPROVE_SOLUTION_ON_BACKGROUND_DURATION = Duration.ofMillis(100);
    private static final Duration WAIT_FOR_IMPROVED_SOLUTION_DURATION = Duration.ofMillis(200);
    private static final BendableLongScore INITIAL_SCORE = BendableLongScore.of(new long[] { 1 }, new long[] { 1 });
    private static final BendableLongScore IMPROVED_SCORE = BendableLongScore.of(new long[] { 2 }, new long[] { 2 });

    @Mock
    private SolverFactory<TaskAssigningSolution> solverFactory;

    @Mock
    private TaskAssigningConfig config;

    @Mock
    private ManagedExecutor managedExecutor;

    @Mock
    private UserServiceConnector userServiceConnector;

    @Mock
    UserServiceConnectorDelegate userServiceConnectorDelegate;

    @Mock
    private UserServiceAdapter userServiceAdapter;

    @Mock
    private BufferedTaskAssigningServiceEventConsumer serviceEventConsumer;

    @Mock
    private ReactiveMessagingEventConsumer serviceMessageConsumer;

    @Mock
    private ClientServices clientServices;

    @Mock
    private SolverExecutor solverExecutor;

    @Mock
    private PlanningExecutor planningExecutor;

    @Mock
    private SolutionDataLoader solutionDataLoader;

    private CompletableFuture<SolutionDataLoader.Result> solutionDataLoaderInitialExecution = new CompletableFuture<>();

    private CompletableFuture<SolutionDataLoader.Result> solutionDataLoaderSecondExecution = new CompletableFuture<>();

    @Captor
    private ArgumentCaptor<Throwable> solutionDataLoaderExecutionErrorCaptor;

    @Mock
    private AttributesProcessorRegistry processorRegistry;

    @Mock
    private Vertx vertx;

    @Mock
    private Event<TaskAssigningService.TimerBasedEvent> timerBasedEvent;

    @Mock
    private OidcClientLookup oidcClientLookup;

    private TaskAssigningServiceContext context;

    @Captor
    private ArgumentCaptor<SolverEventListener<TaskAssigningSolution>> solverListenerCaptor;

    @Captor
    private ArgumentCaptor<Consumer<List<DataEvent<?>>>> dataEventsConsumerCaptor;

    @Captor
    private ArgumentCaptor<DataEvent<?>> dataEventCaptor;

    @Captor
    private ArgumentCaptor<List<PlanningItem>> planningCaptor;

    @Captor
    private ArgumentCaptor<TaskAssigningSolution> solutionCaptor;

    @Captor
    private ArgumentCaptor<List<ProblemFactChange<TaskAssigningSolution>>> problemFactChangesCaptor;

    @Captor
    private ArgumentCaptor<Runnable> managedExecutorCaptor;

    @Captor
    private ArgumentCaptor<Handler<Long>> timerHandlerCaptor;

    @Captor
    private ArgumentCaptor<TaskAssigningService.TimerBasedEvent> timerBasedEventCaptor;

    @Mock
    private SolverEventListener<TaskAssigningSolution> solverEventListener;

    private TaskAssigningService taskAssigningService;

    @BeforeEach
    void setUp() {
        context = spy(new TaskAssigningServiceContext());
        taskAssigningService = spy(new TaskAssigningService());
        taskAssigningService.solverFactory = solverFactory;
        taskAssigningService.config = config;
        taskAssigningService.managedExecutor = managedExecutor;
        taskAssigningService.serviceEventConsumer = serviceEventConsumer;
        taskAssigningService.serviceMessageConsumer = serviceMessageConsumer;
        taskAssigningService.clientServices = clientServices;
        taskAssigningService.userServiceConnector = userServiceConnector;
        taskAssigningService.userServiceConnectorDelegate = userServiceConnectorDelegate;
        taskAssigningService.userServiceAdapter = userServiceAdapter;
        taskAssigningService.solutionDataLoader = solutionDataLoader;
        taskAssigningService.processorRegistry = processorRegistry;
        taskAssigningService.vertx = vertx;
        taskAssigningService.timerBasedEvent = timerBasedEvent;
        taskAssigningService.oidcClientLookup = oidcClientLookup;
    }

    @Test
    void start() throws Exception {
        prepareStart();
    }

    @Test
    void startWithSolverValidationFailure() throws Exception {
        doReturn(new URL(DATA_INDEX_SERVER_URL)).when(config).getDataIndexServerUrl();
        String errorMessage = "Solver factory error";
        doThrow(new RuntimeException(errorMessage)).when(solverFactory).buildSolver();
        assertThatThrownBy(() -> taskAssigningService.start()).hasMessage(errorMessage);
    }

    @Test
    void startWithConfigValidationFailure() {
        doReturn(null).when(config).getDataIndexServerUrl();
        assertThatThrownBy(() -> taskAssigningService.start())
                .hasMessage("The config property: kogito.task-assigning.data-index.server-url must be set with a value");
    }

    @Test
    void startWithOidcClientValidationFailure() throws MalformedURLException {
        String oidcClient = "oidcClient";
        doReturn(new URL(DATA_INDEX_SERVER_URL)).when(config).getDataIndexServerUrl();
        doReturn(Optional.of(oidcClient)).when(config).getOidcClient();
        doReturn(null).when(oidcClientLookup).lookup(oidcClient);
        assertThatThrownBy(() -> taskAssigningService.start())
                .hasMessage("No OidcClient was found for the configured property value kogito.task-assigning.oidc-client = %s", oidcClient);
    }

    @Test
    void startWithUserServiceValidationFailure() throws Exception {
        doReturn(new URL(DATA_INDEX_SERVER_URL)).when(config).getDataIndexServerUrl();
        String errorMessage = "User service validate and get error";
        doThrow(new RuntimeException(errorMessage)).when(userServiceConnector).start();
        assertThatThrownBy(() -> taskAssigningService.start()).hasMessage(errorMessage);
    }

    @Test
    void startWithDataLoadErrors() throws Exception {
        prepareStart();
        Throwable dataLoadException = new Exception("Data loading error");
        solutionDataLoaderInitialExecution.completeExceptionally(dataLoadException);

        verify(taskAssigningService).failFast(solutionDataLoaderExecutionErrorCaptor.capture());
        assertThat(solutionDataLoaderExecutionErrorCaptor.getValue())
                .isNotNull()
                .hasMessageContaining(dataLoadException.getMessage())
                .hasCause(dataLoadException);
    }

    @Test
    void startWithSolutionDataLoadAndNonEmptySolution() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(
                Arrays.asList(
                        mockTaskData(TASK_1_ID, TaskState.READY.value(), TASK_1_LAST_UPDATE),
                        mockTaskData(TASK_2_ID, TaskState.RESERVED.value(), TASK_2_LAST_UPDATE)),
                Collections.singletonList(mockExternalUser(USER_1_ID)));
        prepareStart();
        solutionDataLoaderInitialExecution.complete(result);
        verify(taskAssigningService).onSolutionDataLoad(result);
        verify(solverExecutor).start(any());
        verify(serviceEventConsumer, never()).resume();
        assertThat(context.isTaskPublished(TASK_1_ID)).isFalse();
        assertThat(context.getTaskLastEventTime(TASK_1_ID)).isEqualTo(TASK_1_LAST_UPDATE);
        assertThat(context.isTaskPublished(TASK_2_ID)).isTrue();
        assertThat(context.getTaskLastEventTime(TASK_2_ID)).isEqualTo(TASK_2_LAST_UPDATE);
    }

    @Test
    void startWithSolutionDataLoadAndEmptySolution() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(Collections.emptyList(),
                Collections.emptyList());
        prepareStart();
        solutionDataLoaderInitialExecution.complete(result);
        verify(taskAssigningService).onSolutionDataLoad(result);
        verify(solverExecutor, never()).start(any());
        verify(context, never()).setTaskPublished(anyString(), anyBoolean());
        verify(context, never()).setTaskPublished(anyString(), anyBoolean());
        verify(serviceEventConsumer).resume();
        verify(solverExecutor, never()).start(any());
    }

    @Test
    void startSolutionFromEventsAndEmptySolution() throws Exception {
        prepareStart();
        doReturn(solutionDataLoaderSecondExecution).when(solutionDataLoader).loadSolutionData(false, true, DATA_LOADER_PAGE_SIZE);

        // produce the initial solution data load
        SolutionDataLoader.Result initialEmptyData = new SolutionDataLoader.Result(Collections.emptyList(),
                Collections.emptyList());
        solutionDataLoaderInitialExecution.complete(initialEmptyData);
        verify(taskAssigningService).onSolutionDataLoad(initialEmptyData);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockTaskDataEvent(TASK_1_ID, TaskState.READY.value(), TASK_1_LAST_UPDATE),
                mockTaskDataEvent(TASK_2_ID, TaskState.ABORTED.value(), TASK_2_LAST_UPDATE));

        // produce the events arrival
        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        // produce events arrival in the middle
        List<DataEvent<?>> queuedEventList = Arrays.asList(
                mockTaskDataEvent(TASK_1_ID, TaskState.COMPLETED.value(), TASK_1_LAST_UPDATE.plusSeconds(1)),
                mockTaskDataEvent(TASK_3_ID, TaskState.SKIPPED.value(), TASK_3_LAST_UPDATE));

        doReturn(queuedEventList.size()).when(serviceEventConsumer).queuedEvents();
        doReturn(queuedEventList).when(serviceEventConsumer).pollEvents();

        SolutionDataLoader.Result userData = new SolutionDataLoader.Result(Collections.emptyList(),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        // produce the second solution data load
        solutionDataLoaderSecondExecution.complete(userData);
        verify(taskAssigningService).onSolutionDataLoad(userData);

        verify(solverExecutor, never()).start(any());
        verify(serviceEventConsumer, times(2)).resume();
        verify(context, never()).setTaskPublished(eq(TASK_1_ID), anyBoolean());
        assertThat(context.getTaskLastEventTime(TASK_1_ID)).isEqualTo(TASK_1_LAST_UPDATE.plusSeconds(1));
        verify(context, never()).setTaskPublished(eq(TASK_2_ID), anyBoolean());
        assertThat(context.getTaskLastEventTime(TASK_2_ID)).isEqualTo(TASK_2_LAST_UPDATE);
        verify(context, never()).setTaskPublished(eq(TASK_3_ID), anyBoolean());
        assertThat(context.getTaskLastEventTime(TASK_3_ID)).isEqualTo(TASK_3_LAST_UPDATE);
    }

    @Test
    void startSolutionFromEventsAndNonEmptySolution() throws Exception {
        prepareStart();
        doReturn(solutionDataLoaderSecondExecution).when(solutionDataLoader).loadSolutionData(false, true, DATA_LOADER_PAGE_SIZE);

        // produce the initial solution data load
        SolutionDataLoader.Result initialEmptyData = new SolutionDataLoader.Result(Collections.emptyList(),
                Collections.emptyList());
        solutionDataLoaderInitialExecution.complete(initialEmptyData);
        verify(taskAssigningService).onSolutionDataLoad(initialEmptyData);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockTaskDataEvent(TASK_1_ID, TaskState.READY.value(), TASK_1_LAST_UPDATE),
                mockTaskDataEvent(TASK_2_ID, TaskState.RESERVED.value(), TASK_2_LAST_UPDATE));

        // produce the events arrival
        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        List<DataEvent<?>> queuedEventList = Arrays.asList(
                mockTaskDataEvent(TASK_3_ID, TaskState.READY.value(), TASK_3_LAST_UPDATE),
                mockTaskDataEvent(TASK_4_ID, TaskState.COMPLETED.value(), TASK_4_LAST_UPDATE));

        doReturn(queuedEventList.size()).when(serviceEventConsumer).queuedEvents();
        doReturn(queuedEventList).when(serviceEventConsumer).pollEvents();

        SolutionDataLoader.Result userData = new SolutionDataLoader.Result(Collections.emptyList(),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        // produce the second solution data load
        solutionDataLoaderSecondExecution.complete(userData);
        verify(taskAssigningService).onSolutionDataLoad(userData);

        verify(solverExecutor).start(any());
        assertThat(context.isTaskPublished(TASK_1_ID)).isFalse();
        assertThat(context.getTaskLastEventTime(TASK_1_ID)).isEqualTo(TASK_1_LAST_UPDATE);
        assertThat(context.isTaskPublished(TASK_2_ID)).isTrue();
        assertThat(context.getTaskLastEventTime(TASK_2_ID)).isEqualTo(TASK_2_LAST_UPDATE);
        assertThat(context.isTaskPublished(TASK_3_ID)).isFalse();
        assertThat(context.getTaskLastEventTime(TASK_3_ID)).isEqualTo(TASK_3_LAST_UPDATE);
        verify(context, never()).setTaskPublished(eq(TASK_4_ID), anyBoolean());
        assertThat(context.getTaskLastEventTime(TASK_4_ID)).isEqualTo(TASK_4_LAST_UPDATE);
    }

    @Test
    void onTaskEventsWithExistingSolutionAndThereAreChanges() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(
                Collections.singletonList(mockTaskData(TASK_1_ID, TaskState.RESERVED.value(), USER_1_ID, TASK_1_LAST_UPDATE)),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        prepareStartAndSetInitialSolution(result);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockTaskDataEvent(TASK_2_ID, TaskState.READY.value(), TASK_2_LAST_UPDATE),
                mockTaskDataEvent(TASK_3_ID, TaskState.READY.value(), TASK_3_LAST_UPDATE));

        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(solverExecutor).addProblemFactChanges(problemFactChangesCaptor.capture());
        assertThat(problemFactChangesCaptor.getValue())
                .isNotNull()
                .hasSize(3);

        assertHasAddTaskChangeForTask(problemFactChangesCaptor.getValue(), TASK_2_ID);
        assertHasAddTaskChangeForTask(problemFactChangesCaptor.getValue(), TASK_3_ID);
    }

    @Test
    void onTaskEventsWithExistingSolutionAndThereAreNoChanges() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(
                Collections.singletonList(mockTaskData(TASK_1_ID, TaskState.RESERVED.value(), USER_1_ID, TASK_1_LAST_UPDATE)),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        prepareStartAndSetInitialSolution(result);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockTaskDataEvent(TASK_2_ID, TaskState.ABORTED.value(), TASK_2_LAST_UPDATE),
                mockTaskDataEvent(TASK_3_ID, TaskState.ABORTED.value(), TASK_3_LAST_UPDATE));

        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(solverExecutor, never()).addProblemFactChanges(any());
        verify(serviceEventConsumer, times(2)).resume();
    }

    @Test
    void onSolutionChangeWithPlanningItems() throws Exception {
        prepareStart();
        TaskAssigningSolution solution = buildSolution();

        context.setTaskPublished(TASK_1_ID, false);
        context.setTaskPublished(TASK_2_ID, true);
        context.setTaskPublished(TASK_3_ID, false);
        context.setTaskPublished(TASK_4_ID, true);

        taskAssigningService.onBestSolutionChange(mockEvent(solution, true, true));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(planningExecutor).start(planningCaptor.capture(), any());
        List<PlanningItem> planningItems = planningCaptor.getValue();
        assertThat(planningItems)
                .isNotNull()
                .hasSize(2);
    }

    @Test
    void onSolutionChangeWithNoPlanningItems() throws Exception {
        prepareStart();
        TaskAssigningSolution solution = buildSolution();

        context.setTaskPublished(TASK_1_ID, true);
        context.setTaskPublished(TASK_2_ID, true);
        context.setTaskPublished(TASK_3_ID, true);
        context.setTaskPublished(TASK_4_ID, true);

        taskAssigningService.onBestSolutionChange(mockEvent(solution, true, true));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();
        verify(planningExecutor, never()).start(any(), any());
        verify(serviceEventConsumer).resume();
    }

    @Test
    void onSolutionChangeWhenApplyingPlanningExecutionResult() throws Exception {
        prepareStart();
        TaskAssigningSolution initialSolution = buildSolution();

        context.setTaskPublished(TASK_1_ID, false);
        context.setTaskPublished(TASK_2_ID, true);
        context.setTaskPublished(TASK_3_ID, true);
        context.setTaskPublished(TASK_4_ID, true);

        taskAssigningService.onBestSolutionChange(mockEvent(initialSolution, true, true));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(planningExecutor).start(planningCaptor.capture(), any());
        assertThat(planningCaptor.getValue())
                .isNotNull()
                .hasSize(1);

        PlanningItem planningItem = new PlanningItem(Task.newBuilder().id(TASK_1_ID).build(), USER_1_ID);
        PlanningExecutionResult executionResult = new PlanningExecutionResult(Collections.singletonList(new PlanningExecutionResultItem(planningItem)));
        taskAssigningService.onPlanningExecuted(executionResult);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockTaskDataEvent(TASK_1_ID, TaskState.COMPLETED.value(), TASK_1_LAST_UPDATE.plusSeconds(1)),
                mockTaskDataEvent(TASK_2_ID, TaskState.ABORTED.value(), TASK_2_LAST_UPDATE.plusSeconds(1)));
        doReturn(eventList).when(serviceEventConsumer).pollEvents();

        TaskAssigningSolution newSolution = buildSolution();
        context.setCurrentChangeSetId(context.nextChangeSetId());

        taskAssigningService.onBestSolutionChange(mockEvent(newSolution, true, true));
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(solverExecutor, times(2)).addProblemFactChanges(problemFactChangesCaptor.capture());
        List<ProblemFactChange<TaskAssigningSolution>> changes = problemFactChangesCaptor.getAllValues().get(1);
        assertHasRemoveTaskChangeForTask(changes, TASK_1_ID);
        assertHasRemoveTaskChangeForTask(changes, TASK_2_ID);
    }

    @Test
    void onPlanningExecutedWithPinningChanges() throws Exception {
        prepareStart();
        TaskAssigningSolution solution = new TaskAssigningSolution("1",
                Arrays.asList(mockUser(USER_1_ID, Collections.emptyList()),
                        mockUser(USER_2_ID, Collections.emptyList())),
                Collections.emptyList());

        taskAssigningService.onBestSolutionChange(mockEvent(solution, true, true));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        context.setTaskPublished(TASK_1_ID, false);
        context.setTaskPublished(TASK_2_ID, false);
        PlanningItem planningItem1 = new PlanningItem(Task.newBuilder().id(TASK_1_ID).build(), USER_1_ID);
        PlanningItem planningItem2 = new PlanningItem(Task.newBuilder().id(TASK_2_ID).build(), USER_2_ID);
        PlanningExecutionResult executionResult = new PlanningExecutionResult(
                Arrays.asList(new PlanningExecutionResultItem(planningItem1),
                        new PlanningExecutionResultItem(planningItem2, new RuntimeException("planningItem2 failed"))));

        taskAssigningService.onPlanningExecuted(executionResult);
        assertThat(context.isTaskPublished(TASK_1_ID)).isTrue();
        assertThat(context.isTaskPublished(TASK_2_ID)).isFalse();
        verify(solverExecutor).addProblemFactChanges(problemFactChangesCaptor.capture());
        List<ProblemFactChange<TaskAssigningSolution>> changes = problemFactChangesCaptor.getValue();
        assertThat(changes)
                .isNotNull()
                .hasSize(2);
        assertHasAssignTaskChangeForTask(changes, TASK_1_ID, USER_1_ID);
    }

    @Test
    void onPlanningExecutedWithNoPinningChangesAndNoQueuedEvents() throws Exception {
        preparePlanningExecutionWithNoPinningChanges(0);
        assertThat(context.isTaskPublished(TASK_1_ID)).isFalse();
        assertThat(context.isTaskPublished(TASK_2_ID)).isFalse();
        verify(planningExecutor).start(planningCaptor.capture(), any());
        verify(serviceEventConsumer, times(1)).resume();
        List<PlanningItem> planningItems = planningCaptor.getValue();
        assertThat(planningItems)
                .isNotNull()
                .hasSize(2);
        assertThat(planningItems.get(0).getTask().getId())
                .isEqualTo(TASK_1_ID);
        assertThat(planningItems.get(1).getTask().getId())
                .isEqualTo(TASK_2_ID);
    }

    @Test
    void onPlanningExecutedWithNoPinningChangesAndQueuedEvents() throws Exception {
        preparePlanningExecutionWithNoPinningChanges(1);
        verify(planningExecutor, never()).start(any(), any());
        verify(serviceEventConsumer, times(2)).resume();
    }

    @Test
    void onUserEventAndThereAreChanges() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(
                Collections.singletonList(mockTaskData(TASK_1_ID, TaskState.READY.value(), TASK_1_LAST_UPDATE)),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        prepareStartAndSetInitialSolution(result);

        List<DataEvent<?>> eventList = Arrays.asList(
                mockUserDataEvent(Collections.singletonList(mockExternalUser(USER_1_ID)), USER_DATA_EVENT_1_TIME),
                mockUserDataEvent(Arrays.asList(
                        mockExternalUser(USER_1_ID),
                        mockExternalUser(USER_2_ID),
                        mockExternalUser(USER_3_ID)), USER_DATA_EVENT_2_TIME));

        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(solverExecutor).addProblemFactChanges(problemFactChangesCaptor.capture());
        assertThat(problemFactChangesCaptor.getValue())
                .isNotNull()
                .hasSize(3);

        assertHasAddUserChangeForUser(problemFactChangesCaptor.getValue(), USER_2_ID);
        assertHasAddUserChangeForUser(problemFactChangesCaptor.getValue(), USER_3_ID);
    }

    @Test
    void onUserEventsAndThereAreNoChanges() throws Exception {
        SolutionDataLoader.Result result = new SolutionDataLoader.Result(
                Collections.singletonList(mockTaskData(TASK_1_ID, TaskState.RESERVED.value(), USER_1_ID, TASK_1_LAST_UPDATE)),
                Collections.singletonList(mockExternalUser(USER_1_ID)));

        prepareStartAndSetInitialSolution(result);

        List<DataEvent<?>> eventList = Collections.singletonList(
                mockUserDataEvent(Collections.singletonList(mockExternalUser(USER_1_ID)), USER_DATA_EVENT_1_TIME));

        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(solverExecutor, never()).addProblemFactChanges(any());
        verify(serviceEventConsumer, times(2)).resume();
    }

    @Test
    void onSolutionUpdatedOnBackgroundWithScoreImprovement() throws Exception {
        TaskAssigningSolution newBestSolution = buildSolutionWithScore(IMPROVED_SCORE);
        prepareOnSolutionUpdatedOnBackground(INITIAL_SCORE, newBestSolution);
        verify(taskAssigningService).executeSolutionChange(newBestSolution);
    }

    @Test
    void onSolutionUpdatedOnBackgroundWithNoScoreImprovement() throws Exception {
        TaskAssigningSolution newBestSolution = buildSolutionWithScore(INITIAL_SCORE);
        prepareOnSolutionUpdatedOnBackground(INITIAL_SCORE, newBestSolution);
        verify(taskAssigningService, never()).executeSolutionChange(newBestSolution);
    }

    @SuppressWarnings("unchecked")
    private void prepareOnSolutionUpdatedOnBackground(BendableLongScore initialSolutionScore,
            TaskAssigningSolution newBestSolution) throws Exception {
        prepareStart();
        TaskAssigningSolution initialSolution = buildSolutionWithScore(initialSolutionScore);

        context.setTaskPublished(TASK_1_ID, true);
        context.setTaskPublished(TASK_2_ID, true);
        context.setTaskPublished(TASK_3_ID, true);
        context.setTaskPublished(TASK_4_ID, true);

        doReturn(IMPROVE_SOLUTION_ON_BACKGROUND_DURATION).when(config).getImproveSolutionOnBackgroundDuration();
        doReturn(TIMER_ID).when(vertx).setTimer(eq(IMPROVE_SOLUTION_ON_BACKGROUND_DURATION.toMillis()), any(Handler.class));

        taskAssigningService.onBestSolutionChange(mockEvent(initialSolution));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        taskAssigningService.onBestSolutionChange(mockEvent(newBestSolution));

        List<DataEvent<?>> eventList = Collections.singletonList(new SolutionUpdatedOnBackgroundDataEvent(TIMER_ID, ZonedDateTime.now()));
        dataEventsConsumerCaptor.getValue().accept(eventList);
        verify(managedExecutor, times(2)).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(taskAssigningService).executeSolutionChange(initialSolution);
    }

    @Test
    void onSolutionImprovedOnBackgroundEventIsDeliveredToConsumer() {
        taskAssigningService.onSolutionImprovedOnBackgroundEvent(new TaskAssigningService.SolutionImprovedOnBackgroundEvent(TIMER_ID));
        verify(serviceEventConsumer).accept(dataEventCaptor.capture());
        assertNotNull(dataEventCaptor.getValue());
        assertThat(dataEventCaptor.getValue()).isInstanceOf(SolutionUpdatedOnBackgroundDataEvent.class);
        assertThat(((SolutionUpdatedOnBackgroundDataEvent) dataEventCaptor.getValue()).getData()).isEqualTo(TIMER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onImproveSolutionOnBackgroundTimerFiredProduceTheEvent() throws Exception {
        prepareStart();
        TaskAssigningSolution initialSolution = buildSolutionWithScore(INITIAL_SCORE);

        context.setTaskPublished(TASK_1_ID, true);
        context.setTaskPublished(TASK_2_ID, true);
        context.setTaskPublished(TASK_3_ID, true);
        context.setTaskPublished(TASK_4_ID, true);

        doReturn(IMPROVE_SOLUTION_ON_BACKGROUND_DURATION).when(config).getImproveSolutionOnBackgroundDuration();
        doReturn(TIMER_ID).when(vertx).setTimer(eq(IMPROVE_SOLUTION_ON_BACKGROUND_DURATION.toMillis()), any(Handler.class));

        taskAssigningService.onBestSolutionChange(mockEvent(initialSolution));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(vertx).setTimer(eq(IMPROVE_SOLUTION_ON_BACKGROUND_DURATION.toMillis()), timerHandlerCaptor.capture());
        timerHandlerCaptor.getValue().handle(TIMER_ID);
        verify(timerBasedEvent).fire(timerBasedEventCaptor.capture());
        assertThat(timerBasedEventCaptor.getValue()).isNotNull();
        assertThat(timerBasedEventCaptor.getValue().getTimerId()).isEqualTo(TIMER_ID);
    }

    @Test
    void onWaitForImprovedSolutionTimerFiredProduceTheEvent() throws Exception {
        prepareStart();
        doReturn(WAIT_FOR_IMPROVED_SOLUTION_DURATION).when(config).getWaitForImprovedSolutionDuration();
        doReturn(TIMER_ID).when(vertx).setTimer(eq(WAIT_FOR_IMPROVED_SOLUTION_DURATION.toMillis()), any(Handler.class));

        TaskAssigningSolution initialSolution = buildSolutionWithScore(INITIAL_SCORE);
        taskAssigningService.onBestSolutionChange(mockEvent(initialSolution));
        verify(managedExecutor, never()).runAsync(any());

        verify(vertx).setTimer(anyLong(), timerHandlerCaptor.capture());
        timerHandlerCaptor.getValue().handle(TIMER_ID);
        verify(timerBasedEvent).fire(timerBasedEventCaptor.capture());
        assertThat(timerBasedEventCaptor.getValue()).isNotNull();
        assertThat(timerBasedEventCaptor.getValue().getTimerId()).isEqualTo(TIMER_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onSolutionImproved() throws Exception {
        prepareStart();
        doReturn(WAIT_FOR_IMPROVED_SOLUTION_DURATION).when(config).getWaitForImprovedSolutionDuration();
        doReturn(TIMER_ID).when(vertx).setTimer(anyLong(), any(Handler.class));

        TaskAssigningSolution initialSolution = buildSolutionWithScore(INITIAL_SCORE);
        taskAssigningService.onBestSolutionChange(mockEvent(initialSolution));
        verify(managedExecutor, never()).runAsync(any());

        verify(vertx).setTimer(anyLong(), any(Handler.class));

        TaskAssigningSolution newSolution = buildSolutionWithScore(IMPROVED_SCORE);
        taskAssigningService.onBestSolutionChange(mockEvent(newSolution));
        verify(managedExecutor, never()).runAsync(any());
        verify(taskAssigningService, never()).executeSolutionChange(any());

        taskAssigningService.onSolutionImprovedEvent(new TaskAssigningService.SolutionImprovedEvent(TIMER_ID, initialSolution));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();
        verify(taskAssigningService).executeSolutionChange(newSolution);
    }

    private void preparePlanningExecutionWithNoPinningChanges(int queuedEvents) throws Exception {
        prepareStart();
        TaskAssigningSolution solution = new TaskAssigningSolution("1",
                Arrays.asList(mockUser(USER_1_ID, Collections.emptyList()),
                        mockUser(USER_2_ID, Collections.emptyList())),
                Collections.emptyList());
        taskAssigningService.onBestSolutionChange(mockEvent(solution, true, true));
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();
        context.setTaskPublished(TASK_1_ID, false);
        context.setTaskPublished(TASK_2_ID, false);
        PlanningItem planningItem1 = new PlanningItem(Task.newBuilder().id(TASK_1_ID).build(), USER_1_ID);
        PlanningItem planningItem2 = new PlanningItem(Task.newBuilder().id(TASK_2_ID).build(), USER_2_ID);
        PlanningExecutionResult executionResult = new PlanningExecutionResult(
                Arrays.asList(new PlanningExecutionResultItem(planningItem1, new RuntimeException("planningItem1 failed")),
                        new PlanningExecutionResultItem(planningItem2, new RuntimeException("planningItem2 failed"))));
        doReturn(queuedEvents).when(serviceEventConsumer).queuedEvents();
        taskAssigningService.onPlanningExecuted(executionResult);
    }

    @Test
    void failFast() throws Exception {
        prepareStart();
        RuntimeException error = new RuntimeException("unexpected error was produced");
        taskAssigningService.failFast(error);
        verifyFailFast(error);
    }

    @Test
    void failFastObserver() throws Exception {
        prepareStart();
        RuntimeException error = new RuntimeException("fail fast request error");
        taskAssigningService.onFailFast(new TaskAssigningService.FailFastRequestEvent(error));
        verify(taskAssigningService).failFast(error);
        verifyFailFast(error);
    }

    private void verifyFailFast(Throwable error) {
        verify(solverExecutor).destroy();
        verify(planningExecutor).destroy();
        verify(userServiceAdapter).destroy();
        verify(serviceMessageConsumer).failFast();
        ServiceStatusInfo statusInfo = context.getStatusInfo();
        assertThat(statusInfo).isNotNull();
        assertThat(statusInfo.getStatus()).isEqualTo(ServiceStatus.ERROR);
        assertThat(statusInfo.getStatusMessage().getValue()).contains(error.getMessage());
    }

    @Test
    void onShutDownEvent() throws Exception {
        prepareStart();
        taskAssigningService.onShutDownEvent(new ShutdownEvent());
        verifyDestroy();
    }

    @Test
    void destroy() throws Exception {
        prepareStart();
        taskAssigningService.destroy();
        verifyDestroy();
    }

    @Test
    void createContext() {
        assertThat(taskAssigningService.createContext()).isNotNull();
    }

    @Test
    void createSolverExecutor() {
        SolverExecutor solverExecutor = taskAssigningService.createSolverExecutor(solverFactory, solverEventListener);
        assertThat(solverExecutor).isNotNull();
    }

    @Test
    void createPlanningExecutor() {
        PlanningExecutor planningExecutor = taskAssigningService.createPlanningExecutor(clientServices, config);
        assertThat(planningExecutor).isNotNull();
    }

    private void verifyDestroy() {
        verify(solverExecutor).destroy();
        verify(planningExecutor).destroy();
        verify(userServiceAdapter).destroy();
    }

    private TaskAssigningSolution buildSolution() {
        List<TaskAssignment> user1Assignments = Arrays.asList(mockTaskAssignment(TASK_1_ID),
                mockTaskAssignment(TASK_2_ID));
        User user1 = mockUser(USER_1_ID, user1Assignments);

        List<TaskAssignment> user2Assignments = Arrays.asList(mockTaskAssignment(TASK_3_ID),
                mockTaskAssignment(TASK_4_ID));

        User user2 = mockUser(USER_2_ID, user2Assignments);
        List<TaskAssignment> assignments = new ArrayList<>();
        assignments.addAll(user1Assignments);
        assignments.addAll(user2Assignments);
        return new TaskAssigningSolution("1", Arrays.asList(user1, user2), assignments);
    }

    private TaskAssigningSolution buildSolutionWithScore(BendableLongScore score) {
        TaskAssigningSolution solution = buildSolution();
        solution.setScore(score);
        return solution;
    }

    private void prepareStart() throws Exception {
        doReturn(context).when(taskAssigningService).createContext();
        doReturn(new URL(DATA_INDEX_SERVER_URL)).when(config).getDataIndexServerUrl();
        doReturn(DATA_LOADER_PAGE_SIZE).when(config).getDataLoaderPageSize();
        doReturn(solutionDataLoaderInitialExecution).when(solutionDataLoader).loadSolutionData(true, true, DATA_LOADER_PAGE_SIZE);
        lenient().doReturn(PUBLISH_WINDOW_SIZE).when(config).getPublishWindowSize();
        doReturn(solverExecutor).when(taskAssigningService).createSolverExecutor(eq(solverFactory), solverListenerCaptor.capture());
        doReturn(planningExecutor).when(taskAssigningService).createPlanningExecutor(clientServices, config);

        assertDoesNotThrow(() -> taskAssigningService.start());

        verify(taskAssigningService).createContext();
        verify(serviceEventConsumer).setConsumer(dataEventsConsumerCaptor.capture());
        verify(managedExecutor).execute(solverExecutor);
        verify(managedExecutor).execute(planningExecutor);
        verify(solutionDataLoader).loadSolutionData(true, true, DATA_LOADER_PAGE_SIZE);
    }

    private void prepareStartAndSetInitialSolution(SolutionDataLoader.Result result) throws Exception {
        prepareStart();
        solutionDataLoaderInitialExecution.complete(result);
        verify(taskAssigningService).onSolutionDataLoad(result);
        verify(solverExecutor).start(solutionCaptor.capture());
        TaskAssigningSolution initialSolution = solutionCaptor.getValue();
        BestSolutionChangedEvent<TaskAssigningSolution> solutionChangedEvent = mockEvent(initialSolution, true, true);
        solverListenerCaptor.getValue().bestSolutionChanged(solutionChangedEvent);
        verify(managedExecutor).runAsync(managedExecutorCaptor.capture());
        managedExecutorCaptor.getValue().run();

        verify(serviceEventConsumer).resume();
    }

    private static org.kie.kogito.taskassigning.user.service.User mockExternalUser(String id) {
        org.kie.kogito.taskassigning.user.service.User user = mock(org.kie.kogito.taskassigning.user.service.User.class);
        lenient().doReturn(id).when(user).getId();
        lenient().doReturn(Collections.emptyMap()).when(user).getAttributes();
        lenient().doReturn(Collections.emptySet()).when(user).getGroups();
        return user;
    }

    private static TaskDataEvent mockTaskDataEvent(String taskId, String state, ZonedDateTime lastUpdate) {
        TaskData taskData = mock(TaskData.class);
        doReturn(taskId).when(taskData).getId();
        doReturn(state).when(taskData).getState();
        doReturn(lastUpdate).when(taskData).getLastUpdate();
        return new TaskDataEvent(taskData);
    }

    private static UserDataEvent mockUserDataEvent(List<org.kie.kogito.taskassigning.user.service.User> externalUsers, ZonedDateTime eventTime) {
        return new UserDataEvent(externalUsers, eventTime);
    }

    @SuppressWarnings("unchecked")
    private static BestSolutionChangedEvent<TaskAssigningSolution> mockEvent(TaskAssigningSolution solution,
            boolean allChangesProcessed,
            boolean solutionInitialized) {
        BestSolutionChangedEvent<TaskAssigningSolution> event = mock(BestSolutionChangedEvent.class);
        doReturn(allChangesProcessed).when(event).isEveryProblemFactChangeProcessed();
        BendableLongScore score = BendableLongScore.zero(1, 1).withInitScore(solutionInitialized ? 1 : -1);
        TaskAssigningSolution spySolution = spy(solution);
        doReturn(score).when(spySolution).getScore();
        doReturn(spySolution).when(event).getNewBestSolution();
        return event;
    }

    private static BestSolutionChangedEvent<TaskAssigningSolution> mockEvent(TaskAssigningSolution solution) {
        BestSolutionChangedEvent<TaskAssigningSolution> event = mock(BestSolutionChangedEvent.class);
        doReturn(true).when(event).isEveryProblemFactChangeProcessed();
        doReturn(solution).when(event).getNewBestSolution();
        return event;
    }

    private static void assertHasAddTaskChangeForTask(List<ProblemFactChange<TaskAssigningSolution>> problemFactChanges, String expectedTaskId) {
        List<AddTaskProblemFactChange> addChanges = filterByType(problemFactChanges, AddTaskProblemFactChange.class)
                .filter(addTaskChange -> expectedTaskId.equals(addTaskChange.getTaskAssignment().getId()))
                .collect(Collectors.toList());
        assertThat(addChanges)
                .withFailMessage("One AddTaskProblemFactChange for task: %s is expected, but there are %s.",
                        expectedTaskId, addChanges.size())
                .hasSize(1);
    }

    private static void assertHasRemoveTaskChangeForTask(List<ProblemFactChange<TaskAssigningSolution>> problemFactChanges, String expectedTaskId) {
        List<RemoveTaskProblemFactChange> removeChanges = filterByType(problemFactChanges, RemoveTaskProblemFactChange.class)
                .filter(removeTaskChange -> expectedTaskId.equals(removeTaskChange.getTaskAssignment().getId()))
                .collect(Collectors.toList());
        assertThat(removeChanges)
                .withFailMessage("One RemoveTaskProblemFactChange for task: %s is expected, but there are %s.",
                        expectedTaskId, removeChanges.size())
                .hasSize(1);
    }

    private static void assertHasAssignTaskChangeForTask(List<ProblemFactChange<TaskAssigningSolution>> problemFactChanges,
            String expectedTaskId, String expectedUserId) {
        List<AssignTaskProblemFactChange> assignChanges = filterByType(problemFactChanges, AssignTaskProblemFactChange.class)
                .filter(assignChange -> expectedTaskId.equals(assignChange.getTaskAssignment().getId()))
                .filter(assignChange -> expectedUserId.equals(assignChange.getUser().getId()))
                .collect(Collectors.toList());
        assertThat(assignChanges)
                .withFailMessage("One AssignTaskProblemFactChange for task: %s and user: %s is expected, but there are %s.",
                        expectedTaskId, expectedUserId, assignChanges.size())
                .hasSize(1);
    }

    private static void assertHasAddUserChangeForUser(List<ProblemFactChange<TaskAssigningSolution>> problemFactChanges, String expectedUserId) {
        List<AddUserProblemFactChange> addChanges = filterByType(problemFactChanges, AddUserProblemFactChange.class)
                .filter(addUserChange -> expectedUserId.equals(addUserChange.getUser().getId()))
                .collect(Collectors.toList());
        assertThat(addChanges)
                .withFailMessage("One AddUserProblemFactChange for user: %s is expected, but there are %s.",
                        expectedUserId, addChanges.size())
                .hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static <T extends ProblemFactChange<TaskAssigningSolution>> Stream<T> filterByType(List<? extends ProblemFactChange<TaskAssigningSolution>> changes, Class<T> clazz) {
        return changes.stream()
                .filter(Objects::nonNull)
                .filter(clazz::isInstance)
                .map(change -> (T) change);
    }
}
