package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class MdnsModuleVerifyTest :
    FunSpec({
        test("mdnsModule resolves InstanceIdentity and the advertiser factory") {
            withInMemoryDatabase {
                val db: Database = this
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                val app =
                    koinApplication {
                        modules(
                            module {
                                single<Database> { db }
                                single { ServerSettingsRepository(get<Database>(), RegistrationPolicy.CLOSED) }
                            },
                            mdnsModule(scope, port = 8080),
                        )
                    }
                try {
                    app.koin.get<InstanceIdentity>() shouldNotBe null
                    app.koin.get<MdnsAdvertiser> {
                        parametersOf("test-id", "Test Library", "https://listen.example.com")
                    } shouldNotBe null
                } finally {
                    app.close()
                }
            }
        }

        // Regression: singleLabelHostname guards against FQDN input that would corrupt
        // DNS name encoding. A dotted hostname passed as instanceName would be split by
        // DnsCodec.encodeName into extra labels, producing invalid DNS names.
        test("singleLabelHostname strips FQDN to the first label") {
            singleLabelHostname("host.example.com") shouldBe "host"
        }

        test("singleLabelHostname returns fallback for a blank input") {
            singleLabelHostname("") shouldBe "listenup-server"
        }

        test("singleLabelHostname passes through a label with no dots unchanged") {
            singleLabelHostname("myserver") shouldBe "myserver"
        }
    })
