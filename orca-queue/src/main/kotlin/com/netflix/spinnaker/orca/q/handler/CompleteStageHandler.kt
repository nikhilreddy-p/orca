/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.histogram.BucketCounter
import com.netflix.spinnaker.orca.ExecutionStatus.*
import com.netflix.spinnaker.orca.events.StageComplete
import com.netflix.spinnaker.orca.ext.afterStages
import com.netflix.spinnaker.orca.ext.firstAfterStages
import com.netflix.spinnaker.orca.ext.syntheticStages
import com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.graph.StageGraphBuilder
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.q.Queue
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.TimeUnit

@Component
class CompleteStageHandler(
  override val queue: Queue,
  override val repository: ExecutionRepository,
  @Qualifier("queueEventPublisher") private val publisher: ApplicationEventPublisher,
  private val clock: Clock,
  override val contextParameterProcessor: ContextParameterProcessor,
  private val registry: Registry,
  override val stageDefinitionBuilderFactory: StageDefinitionBuilderFactory
) : OrcaMessageHandler<CompleteStage>, StageBuilderAware, ExpressionAware {

  override fun handle(message: CompleteStage) {
    message.withStage { stage ->
      if (stage.status in setOf(RUNNING, NOT_STARTED)) {
        val status = stage.determineStatus()
        if (status.isComplete && !status.isHalt) {
          // check to see if this stage has any unplanned synthetic after stages
          var afterStages = stage.firstAfterStages()
          if (afterStages.isEmpty()) {
            stage.planAfterStages()

            afterStages = stage.firstAfterStages()
            if (afterStages.isNotEmpty()) {
              afterStages.forEach {
                queue.push(StartStage(message, it.id))
              }

              return@withStage
            }
          }
        }

        if (status.isFailure) {
          if (stage.planOnFailureStages()) {
            stage.firstAfterStages().forEach {
              queue.push(StartStage(it))
            }
            return@withStage
          }
        }

        stage.status = status
        stage.endTime = clock.millis()
        stage.includeExpressionEvaluationSummary()
        repository.storeStage(stage)

        if (status in listOf(SUCCEEDED, FAILED_CONTINUE)) {
          stage.startNext()
        } else {
          queue.push(CancelStage(message))
          if (stage.syntheticStageOwner == null) {
            log.debug("Stage has no synthetic owner, completing execution (original message: $message)")
            queue.push(CompleteExecution(message))
          } else {
            queue.push(message.copy(stageId = stage.parentStageId!!))
          }
        }

        publisher.publishEvent(StageComplete(this, stage))
        trackResult(stage)
      }
    }
  }

  private fun trackResult(stage: Stage) {
    // We only want to record durations of parent-level stages; not synthetics.
    if (stage.parentStageId != null) {
      return
    }

    val id = registry.createId("stage.invocations.duration")
      .withTag("status", stage.status.toString())
      .withTag("stageType", stage.type)
      .let { id ->
        // TODO rz - Need to check synthetics for their cloudProvider.
        stage.context["cloudProvider"]?.let {
          id.withTag("cloudProvider", it.toString())
        } ?: id
      }

    BucketCounter
      .get(registry, id, { v -> bucketDuration(v) })
      .record((stage.endTime ?: clock.millis()) - (stage.startTime ?: 0))
  }

  private fun bucketDuration(duration: Long) =
    when {
      duration > TimeUnit.MINUTES.toMillis(60) -> "gt60m"
      duration > TimeUnit.MINUTES.toMillis(30) -> "gt30m"
      duration > TimeUnit.MINUTES.toMillis(15) -> "gt15m"
      duration > TimeUnit.MINUTES.toMillis(5) -> "gt5m"
      else -> "lt5m"
    }

  override val messageType = CompleteStage::class.java

  /**
   * Plan any outstanding synthetic after stages.
   */
  private fun Stage.planAfterStages() {
    var hasPlannedStages = false

    builder().buildAfterStages(this) { it: Stage ->
      repository.addStage(it)
      hasPlannedStages = true
    }

    if (hasPlannedStages) {
      this.execution = repository.retrieve(this.execution.type, this.execution.id)
    }
  }

  /**
   * Plan any outstanding synthetic on failure stages.
   */
  private fun Stage.planOnFailureStages(): Boolean {
    // Avoid planning failure stages if _any_ with the same name are already complete
    val previouslyPlannedAfterStageNames = afterStages().filter { it.status.isComplete }.map { it.name }

    val graph = StageGraphBuilder.afterStages(this)
    builder().onFailureStages(this, graph)
    val onFailureStages = graph.build().toList()

    val alreadyPlanned = onFailureStages.any { previouslyPlannedAfterStageNames.contains(it.name) }

    return if (alreadyPlanned || onFailureStages.isEmpty()) {
      false
    } else {
      removeNotStartedSynthetics() // should be all synthetics (nothing should have been started!)
      appendAfterStages(onFailureStages) {
        repository.addStage(it)
      }
      true
    }
  }

  private fun Stage.removeNotStartedSynthetics() {
    syntheticStages()
      .filter { it.status == NOT_STARTED }
      .forEach { stage ->
        execution
          .stages
          .filter { it.requisiteStageRefIds.contains(stage.id) }
          .forEach {
            it.requisiteStageRefIds = it.requisiteStageRefIds - stage.id
            repository.addStage(it)
          }
        stage.removeNotStartedSynthetics() // should be all synthetics!
        repository.removeStage(execution, stage.id)
      }
  }
}
