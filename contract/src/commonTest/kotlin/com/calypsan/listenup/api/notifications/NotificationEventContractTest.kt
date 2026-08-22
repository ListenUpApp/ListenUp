package com.calypsan.listenup.api.notifications

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NotificationEventContractTest :
    FunSpec({
        val cases: List<NotificationEvent> =
            listOf(
                NotificationEvent.CampfireInvite(campfireId = "cf-1", bookId = "b-1", inviterUserId = "u-1"),
                NotificationEvent.RegistrationDecision(userId = "u-7", approved = true),
                NotificationEvent.RegistrationApproval(userId = "u-9"),
            )

        test("every case round-trips through contractJson") {
            cases.forEach { event ->
                val json = contractJson.encodeToString(NotificationEvent.serializer(), event)
                val decoded = contractJson.decodeFromString(NotificationEvent.serializer(), json)
                decoded shouldBe event
            }
        }

        test("discriminators are wire-stable") {
            cases.map { event ->
                contractJson
                    .encodeToJsonElement(NotificationEvent.serializer(), event)
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            } shouldContainExactlyInAnyOrder listOf("campfire_invite", "registration_decision", "registration_approval")
        }

        test("wireType matches the serialized discriminator on every case") {
            cases.forEach { event ->
                val discriminator =
                    contractJson
                        .encodeToJsonElement(NotificationEvent.serializer(), event)
                        .jsonObject["type"]!!
                        .jsonPrimitive.content
                event.wireType shouldBe discriminator
            }
        }

        test("the NotificationTypes registry covers exactly the sealed cases") {
            // The sealed serializer's element 1 ("value") enumerates every subclass descriptor —
            // works in commonTest, no JVM reflection needed.
            val declared =
                NotificationEvent
                    .serializer()
                    .descriptor
                    .getElementDescriptor(1)
                    .elementDescriptors
                    .map { it.serialName }
                    .toSet()
            NotificationTypes.all.keys shouldBe declared
        }

        test("every case's descriptor is the registry's descriptor for its wireType") {
            cases.forEach { event ->
                NotificationTypes.all[event.wireType] shouldBe event.descriptor
            }
        }
    })
