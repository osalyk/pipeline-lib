/*
 * (C) Copyright 2026 Hewlett Packard Enterprise Development LP
 *
 * SPDX-License-Identifier: BSD-2-Clause-Patent
 */

import static helpers.Bindings.*
import static org.junit.jupiter.api.Assertions.*

import groovy.lang.Binding
import groovy.lang.GroovyShell
import java.util.stream.Stream
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class UnitTest {

    static final String CONTEXT_MOCK = 'ctx'
    static final String DESCRIPTION_MOCK = 'desc'
    static final String JUNIT_FILES_DEFAULT = 'test_results/*.xml'
    static final String NODELIST_DEFAULT = 'node1'
    static final int NODE_COUNT_DEFAULT = 1
    static final String INST_RPMS_DEFAULT = ''
    static final String IMAGE_VERSION_MOCK = 'el9'
    static final String BUILD_TYPE_MOCK = 'debug'
    static final String COMPILER_MOCK = 'gcc'
    static final List STASHES_MOCK = [
        'el9-gcc-tests',
        'el9-gcc-build-vars',
        'el9-gcc-install'
    ]

    private Object loadScriptWithMocks(Map extraBinding = [:]) {

        def binding = new Binding()

        // ---- ENV ----
        binding.setVariable('env', [
            NODELIST        : UnitTest.NODELIST_DEFAULT,
            STAGE_NAME      : 'el9-gcc',
            SSH_KEY_ARGS    : ''
        ])

        // ---- PIPELINE STEP MOCKS ----
        commonBindings(binding)

        // ---- INTERNAL LIBRARY STEPS ----

        binding.setVariable('provisionNodes', { Map m ->
            [:]
        })

        binding.setVariable('runTest', { Map m ->
            [result_code: 0]
        })

        binding.setVariable('parseStageInfo', { Map m ->
            [
                compiler        : 'gcc',
                node_count      : UnitTest.NODE_COUNT_DEFAULT,
                target          : 'el9',
                distro_version  : '9',
                ci_target       : 'el9',
                build_type      : '',
                NLT             : false
            ]
        })

        binding.setVariable('durationSeconds', { Long l -> 5 })
        binding.setVariable('sanitizedStageName', { -> 'el9-gcc' })
        binding.setVariable('checkJunitFiles', { Map m -> 'SUCCESS' })

        // override bindings as required for a specific test
        extraBinding.each { k, v ->
            binding.setVariable(k, v)
        }

        def shell = new GroovyShell(binding)
        return shell.parse(new File('vars/unitTest.groovy'))
    }

    @Test
    void 'provisionNodes() gets basic arguments'() {
        def provisionNodes = { Map m ->
            assertNotNull(m)
            assertEquals(UnitTest.NODELIST_DEFAULT, m.NODELIST)
            assertEquals(UnitTest.NODE_COUNT_DEFAULT, m.node_count)
            assertEquals(UnitTest.IMAGE_VERSION_MOCK, m.distro)
            assertEquals(UnitTest.INST_RPMS_DEFAULT, m.inst_rpms)

            return [:]
        }

        def script = loadScriptWithMocks([
            provisionNodes: provisionNodes
        ])

        script.call([
            /*
             * It is not the default path but it is the simpler one.
             * The default is tested later on.
             */
            image_version: UnitTest.IMAGE_VERSION_MOCK
        ])
    }

    @Test
    void 'runTest() gets basic arguments'() {
        def runTest = { Map m ->
            assertNotNull(m)
            assertEquals(UnitTest.STASHES_MOCK, m.stashes)
            // assertEquals(WIP, m.script)
            assertEquals(UnitTest.JUNIT_FILES_DEFAULT, m.junit_files)
            assertEquals(UnitTest.CONTEXT_MOCK, m.context)
            assertEquals(UnitTest.DESCRIPTION_MOCK, m.description)
            assertTrue(m.ignore_failure)
            assertFalse(m.notify_result)

            return [:]
        }

        def script = loadScriptWithMocks([
            runTest: runTest
        ])

        script.call([
            /*
             * It is not the default path but it is the simpler one.
             * The default is tested later on.
             */
            stashes: UnitTest.STASHES_MOCK,
            context: UnitTest.CONTEXT_MOCK,
            description: UnitTest.DESCRIPTION_MOCK
        ])
    }

    static Stream<Arguments> variants() {
        return Stream.of(
                Arguments.of(null, true, false),
                Arguments.of(UnitTest.BUILD_TYPE_MOCK, true, false),
                Arguments.of(UnitTest.BUILD_TYPE_MOCK, false, false),
                Arguments.of(UnitTest.BUILD_TYPE_MOCK, false, true),
                Arguments.of(UnitTest.BUILD_TYPE_MOCK, true, true),
                )
    }

    @ParameterizedTest(name = "build_type: ''{0}'', unstash_tests: ''{1}'', unstash_opt: ''{2}''")
    @MethodSource('variants')
    void "runTest() stashes variants with image_version"(String build_type, boolean unstash_tests, boolean unstash_opt) {
        def parseStageInfo = { Map m ->
            Map stage_info = [
                compiler : UnitTest.COMPILER_MOCK,
            ]
            if (build_type != null) {
                stage_info['build_type'] = build_type
            }
            return stage_info
        }

        def runTest = { Map m ->
            String ts = UnitTest.IMAGE_VERSION_MOCK + '-' + UnitTest.COMPILER_MOCK
            if (build_type != null) {
                ts += '-' + UnitTest.BUILD_TYPE_MOCK
            }
            List stashes = [ts + '-build-vars']
            if (unstash_tests) stashes.add(ts + '-tests')
            if (unstash_opt) {
                stashes.add(ts + '-opt-tar')
            } else {
                stashes.add(ts + '-install')
            }
            assertNotNull(m)
            assertIterableEquals(stashes.sort(), m.stashes.sort().collect { it.toString() })

            return [:]
        }

        def script = loadScriptWithMocks([
            parseStageInfo: parseStageInfo,
            runTest: runTest
        ])

        script.call([
            image_version: UnitTest.IMAGE_VERSION_MOCK,
            unstash_tests: unstash_tests,
            unstash_opt: unstash_opt,
        ])
    }

    @Test
    void 'call uses correct timeout parameters'() {

        def capturedTimeout = null

        def script = loadScriptWithMocks([
            timeout: { Map m, Closure c ->
                capturedTimeout = m
                c()
            },
            runTest: { Map cfg ->
                [result_code: 0]
            }
        ])

        script.call([
            timeout_time: 60,
            timeout_unit: 'MINUTES'
        ])

        assertNotNull(capturedTimeout)
        assertEquals(60, capturedTimeout.time)
        assertEquals('MINUTES', capturedTimeout.unit)
    }

    @Test
    void 'call returns correct runData'() {

        def script = loadScriptWithMocks([
            runTest: { Map cfg -> [result_code: 0] },
            afterTest: { Map cfg, Map run ->
                run
            }
        ])

        def result = script.call([:])

        assertEquals(0, result.result_code)
        assertEquals(5, result.unittest_time)  // durationSeconds mock
    }

    @Test
    void 'call computes default image_version correctly'() {

        def capturedProvisionArgs = null

        def script = loadScriptWithMocks([
            provisionNodes: { Map m ->
                capturedProvisionArgs = m
                return [:]
            }
        ])

        script.call([:])

        assertNotNull(capturedProvisionArgs)
        assertEquals('el9', capturedProvisionArgs.distro)  // image_version
    }

    @Test
    void 'call uses provided image_version when given'() {

        def capturedProvisionArgs = null

        def script = loadScriptWithMocks([
            provisionNodes: { Map m ->
                capturedProvisionArgs = m
                return [:]
            }
        ])

        script.call([
            image_version: 'el9.7'
        ])

        assertNotNull(capturedProvisionArgs)
        assertEquals('el9.7', capturedProvisionArgs.distro)
    }
}
