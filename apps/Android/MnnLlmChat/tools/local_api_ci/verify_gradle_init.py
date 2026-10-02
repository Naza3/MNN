#!/usr/bin/env python3
"""Exercise CI task registration with real Gradle and synthetic, SDK-free projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def create_fixture(directory):
    root = directory/'repo'
    project = root/'apps/Android/MnnLlmChat'
    app = project/'app'
    unrelated = project/'unrelated'
    local = project/'local_lib'
    app.mkdir(parents=True)
    unrelated.mkdir()
    local.mkdir()
    (project/'settings.gradle').write_text("rootProject.name = 'ci-init-fixture'\ninclude ':app', ':unrelated', ':local_lib'\n")
    (project/'build.gradle').write_text('// SDK-free task registration fixture\n')
    (project/'gradle.properties').write_text('org.gradle.configureondemand=true\n')
    (unrelated/'build.gradle').write_text("throw new GradleException('Unrelated project must remain unconfigured')\n")
    (app/'build.gradle').write_text('''
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Category
repositories { maven { url = rootProject.file('fixture-maven') } }
configurations {
    standardReleaseRuntimeClasspath {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
        }
    }
}
dependencies {
    standardReleaseRuntimeClasspath 'fixture:external-jar:1.0'
    standardReleaseRuntimeClasspath project(':local_lib')
}
tasks.register('legacyAmbiguousInventory') {
    doLast { configurations.standardReleaseRuntimeClasspath.resolvedConfiguration.resolvedArtifacts.each { println it.file.name } }
}
tasks.register('preBuild')
tasks.register('assembleStandardRelease') {
    dependsOn 'preBuild'
    doLast { println 'Synthetic assembly task executed; no Android/native artifact was built' }
}
''')
    (local/'build.gradle').write_text('''
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Attribute
repositories { maven { url = rootProject.file('fixture-maven') } }
def classesJar = tasks.register('classesJar', Jar) {
    archiveFileName = 'local-runtime.jar'
    destinationDirectory = layout.buildDirectory.dir('runtime')
    from('fixture-classes')
}
def runtime = configurations.create('releaseRuntimeElements') {
    canBeResolved = false
    canBeConsumed = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
    }
}
dependencies { releaseRuntimeElements 'fixture:external-aar:1.0' }
runtime.outgoing.variants.create('android-classes-jar') {
    attributes { attribute(Attribute.of('artifactType', String), 'android-classes-jar') }
    artifact(classesJar)
}
['android-assets', 'android-aar-metadata', 'android-art-profile'].each { type ->
    runtime.outgoing.variants.create(type) {
        attributes { attribute(Attribute.of('artifactType', String), type) }
        artifact(file('fixture-side-artifact.txt'))
    }
}
''')
    (local/'fixture-side-artifact.txt').write_text('Synthetic secondary artifact; must not be inventoried as classes')
    (local/'fixture-classes').mkdir()
    (local/'fixture-classes/content.txt').write_text('Synthetic project class artifact fixture')
    for name, extension in [('external-jar','jar'), ('external-aar','aar')]:
        module = project/'fixture-maven/fixture'/name/'1.0'
        module.mkdir(parents=True)
        (module/(name+'-1.0.pom')).write_text(f'<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>{name}</artifactId><version>1.0</version><packaging>{extension}</packaging></project>')
        with zipfile.ZipFile(module/(name+'-1.0.'+extension),'w') as archive:
            archive.writestr('META-INF/LICENSE','Synthetic fixture license')
            archive.writestr('fixture.txt',name+' original untransformed artifact')
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
    (root/'.gitignore').write_text('**/build/\n**/.gradle/\n')
    subprocess.run(['git','init','-q',str(root)],check=True)
    subprocess.run(['git','-C',str(root),'add','.'],check=True)
    subprocess.run(['git','-C',str(root),'-c','user.name=CI Fixture','-c','user.email=fixture@example.invalid','commit','-qm','Synthetic regression fixture'],check=True)
    commit = subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    (report/'source-provenance.json').write_text(json.dumps({'app_commit_sha':commit,'synthetic_fixture':True}))
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
        env['MNN_ENGINE_SOURCE_ROOT'] = str(mnn.parents[4])
        env['MNN_ENGINE_INSTALL_ROOT'] = str(mnn.parents[1])
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
        rows = json.loads(inventory.read_text()) if inventory.is_file() else []
        expected = {}
        for name, extension in [('external-jar','jar'), ('external-aar','aar')]:
            artifact = project/'fixture-maven/fixture'/name/'1.0'/(name+'-1.0.'+extension)
            expected[name] = digest(artifact.read_bytes())
        if {row['name']:row['sha256'] for row in rows} != expected:
            raise RuntimeError('External direct JAR/transitive AAR inventory did not preserve exact original hashes')
        local_rows = json.loads((fixture_report/'local-project-inventory.json').read_text())
        local_jar = project/'local_lib/build/runtime/local-runtime.jar'
        if len(local_rows) != 1 or local_rows[0]['project_path'] != ':local_lib' or local_rows[0]['sha256'] != digest(local_jar.read_bytes()):
            raise RuntimeError('Local classes artifact/producer dependency was not correctly inventoried')
        expected_tree = subprocess.check_output(['git','-C',str(project),'rev-parse','HEAD:apps/Android/MnnLlmChat/local_lib'],text=True).strip()
        expected_commit = subprocess.check_output(['git','-C',str(project),'rev-parse','HEAD'],text=True).strip()
        if (local_rows[0]['artifact_type'] != 'android-classes-jar'
                or local_rows[0]['attributes'].get('artifactType') != 'android-classes-jar'
                or local_rows[0]['source_tree_sha'] != expected_tree
                or local_rows[0]['source_commit_sha'] != expected_commit):
            raise RuntimeError('Local selected artifact/source provenance is incomplete')
        inventory_evidence = {'external_artifacts': rows, 'local_project_artifacts': local_rows}
        execute('former_untyped_resolution_is_ambiguous', script,
                [':app:legacyAmbiguousInventory'], False, 'cannot choose between')
        app_build = project/'app/build.gradle'
        original_build = app_build.read_text()
        opaque = project/'app/unsupported-runtime.jar'
        with zipfile.ZipFile(opaque,'w') as archive:
            archive.writestr('fixture.txt','Unprovenanced flat-file artifact')
        app_build.write_text(original_build + "\ndependencies { standardReleaseRuntimeClasspath files('unsupported-runtime.jar') }\n")
        execute('unprovenanced_flat_file_artifact_is_rejected', script,
                [':app:localApiDependencyInventory'], False,
                'Uninventoried non-module/non-project runtime artifacts')
        app_build.write_text(original_build)
        opaque.unlink()
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
            'cases':cases, 'inventory_evidence':inventory_evidence},indent=2)+'\n')
    print('Real Gradle init-script regression checks passed; no Android/native build ran')


if __name__ == '__main__':
    main()
