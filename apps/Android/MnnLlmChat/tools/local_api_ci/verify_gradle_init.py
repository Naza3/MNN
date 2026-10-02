#!/usr/bin/env python3
"""Exercise CI task registration with real Gradle and synthetic, SDK-free projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def create_fixture(directory):
    root = directory/'repo'
    project = root/'apps/Android/MnnLlmChat'
    app = project/'app'
    unrelated = project/'unrelated'
    app.mkdir(parents=True)
    unrelated.mkdir()
    (project/'settings.gradle').write_text("rootProject.name = 'ci-init-fixture'\ninclude ':app', ':unrelated'\n")
    (project/'build.gradle').write_text('// SDK-free task registration fixture\n')
    (project/'gradle.properties').write_text('org.gradle.configureondemand=true\n')
    (unrelated/'build.gradle').write_text("throw new GradleException('Unrelated project must remain unconfigured')\n")
    (app/'build.gradle').write_text('''
configurations { standardReleaseRuntimeClasspath }
tasks.register('preBuild')
tasks.register('assembleStandardRelease') {
    dependsOn 'preBuild'
    doLast { println 'Synthetic assembly task executed; no Android/native artifact was built' }
}
''')
    lock = json.loads((HERE/'build-lock.json').read_text())
    lock_path = project/'tools/local_api_ci/build-lock.json'
    lock_path.parent.mkdir(parents=True)
    lock_path.write_text(json.dumps(lock))
    mnn = root/'project/android/build_64/lib/libMNN.so'
    mnn.parent.mkdir(parents=True)
    mnn.write_bytes(b'Synthetic Gradle guard fixture only; not an ELF library')
    sherpa = app/'src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so'
    sherpa.parent.mkdir(parents=True)
    sherpa.write_bytes(b'Synthetic Sherpa guard fixture only; never packaged')
    report = directory/'reports'
    report.mkdir()
    inputs = report/'sherpa-source-inputs.json'
    inputs.write_text('{"synthetic_fixture":true}\n')
    provenance = report/'sherpa-provenance.json'
    provenance.write_text(json.dumps({'source_kind':'built_from_locked_source',
        'library_sha256':digest(sherpa.read_bytes()), 'mnn_library_sha256':digest(mnn.read_bytes()),
        'engine_base_sha':lock['engine_base_sha'], 'source_root':lock['sherpa']['source_root'],
        'source_tree_sha':lock['sherpa']['source_tree_sha'], 'source_inputs_report_sha256':digest(inputs.read_bytes())}))
    return project, report, mnn


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--gradle', type=Path)
    group.add_argument('--wrapper', type=Path)
    parser.add_argument('--report-dir', type=Path, required=True)
    parser.add_argument('--negative-control', action='store_true', help='Also prove the former late-registration pattern fails')
    args = parser.parse_args()
    launcher = [str(args.gradle.resolve())] if args.gradle else ['bash', str(args.wrapper.resolve())]
    with tempfile.TemporaryDirectory(prefix='mnn-gradle-init-fixture-') as temporary:
        directory = Path(temporary)
        project, fixture_report, mnn = create_fixture(directory)
        env = os.environ.copy()
        env['REPORT_DIR'] = str(fixture_report)
        command = launcher + ['--project-dir', str(project), '--configure-on-demand', '--no-daemon',
                              '--console=plain', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx512m']
        cases = []
        def execute(name, script, tasks, expected_success, marker=None):
            result = subprocess.run(command + ['--init-script', str(script)] + tasks, env=env,
                                    capture_output=True, text=True, timeout=180)
            success = result.returncode == 0
            output = result.stdout + result.stderr
            passed = success == expected_success and (marker is None or marker in output)
            cases.append({'case':name, 'passed':passed, 'exit_code':result.returncode})
            if not passed:
                print(output[-20000:])
                raise RuntimeError('Gradle init regression failed: ' + name)
        script = HERE/'ci.init.gradle'
        execute('explicit_inventory_with_configuration_on_demand', script,
                [':app:localApiDependencyInventory'], True)
        inventory = fixture_report/'dependency-inventory.json'
        if not inventory.is_file() or json.loads(inventory.read_text()) != []:
            raise RuntimeError('Synthetic inventory task did not actually write its expected empty dependency report')
        execute('combined_assembly_and_inventory_task_selection', script,
                [':app:assembleStandardRelease', ':app:localApiDependencyInventory'], True,
                'Synthetic assembly task executed')
        mnn.write_bytes(b'Changed synthetic MNN fixture')
        execute('stale_mnn_source_guard_rejects_assembly', script,
                [':app:assembleStandardRelease'], False, 'Sherpa library does not match the recorded source build')
        if args.negative_control:
            old = directory/'late-registration.init.gradle'
            text = script.read_text()
            text = text.replace("gradle.beforeProject { app ->\n    if (app.path != ':app') return",
                                "gradle.projectsEvaluated {\n    def app = gradle.rootProject.project(':app')")
            old.write_text(text)
            execute('former_projects_evaluated_registration_fails', old,
                    [':app:localApiDependencyInventory'], False,
                    "task 'localApiDependencyInventory' not found in project ':app'")
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir/'gradle-init-regression.json').write_text(json.dumps({
            'scope':'SDK-free synthetic task discovery and guard fixtures; no native/App build',
            'cases':cases},indent=2)+'\n')
    print('Real Gradle init-script regression checks passed; no Android/native build ran')


if __name__ == '__main__':
    main()
