#!/usr/bin/env python3
"""Verify the installed Minecraft platform extension using offline Java loader stubs.

Prepare with :obfaryn-cli:installDist :platforms:mc-mod:jar on JDK 21.
This does not start Fabric, Forge, NeoForge, or Minecraft.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'obfaryn-cli/build/install/obfaryn/bin/obfaryn'


def run(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def main():
    version = next(line.split('=', 1)[1].strip()
                   for line in (ROOT / 'gradle.properties').read_text().splitlines()
                   if line.startswith('releaseVersion='))
    extension = ROOT / f'platforms/mc-mod/build/libs/obfaryn-platform-mc-mod-{version}.jar'
    if not CLI.is_file() or not extension.is_file():
        raise RuntimeError('Run :obfaryn-cli:installDist :platforms:mc-mod:jar first')
    loaders = [
        ('fabric', 'fabric.mod.json', None, None),
        ('forge', 'META-INF/mods.toml', 'net.minecraftforge.fml.common',
         'net.minecraftforge.eventbus.api'),
        ('neoforge', 'META-INF/neoforge.mods.toml', 'net.neoforged.fml.common',
         'net.neoforged.bus.api'),
    ]
    for loader, descriptor, mod_package, event_package in loaders:
        with tempfile.TemporaryDirectory(prefix=f'obfaryn-{loader}-extension-') as temp:
            work = Path(temp)
            sources = work / 'src'
            files = []

            def source(name, text):
                path = sources / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding='utf-8')
                files.append(path)

            if loader == 'fabric':
                source('net/fabricmc/api/ModInitializer.java',
                       'package net.fabricmc.api; public interface ModInitializer { void onInitialize(); }')
                declaration = 'implements net.fabricmc.api.ModInitializer'
                mod_annotation = event_annotation = ''
                initializer = 'public void onInitialize() { Tasks.run(); }'
            else:
                for package, name, target in [(mod_package, 'Mod', 'TYPE'),
                                               (event_package, 'SubscribeEvent', 'METHOD')]:
                    source(package.replace('.', '/') + '/' + name + '.java',
                           f'package {package}; import java.lang.annotation.*; '
                           f'@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.{target}) '
                           f'public @interface {name} {{}}')
                declaration = initializer = ''
                mod_annotation = f'@{mod_package}.Mod'
                event_annotation = f'@{event_package}.SubscribeEvent'
            source('fixture/ExampleMod.java', f'''package fixture;
{mod_annotation} public final class ExampleMod {declaration} {{
  {initializer}
  public static void main(String[] args) {{
    Tasks.run();
    if (Tasks.result != 42 || Tasks.unrelated(5) != 20) throw new AssertionError();
    System.out.println("{loader}-extension-ok");
  }}
}}''')
            source('fixture/Tasks.java', f'''package fixture;
public final class Tasks {{
  public static int result;
  {event_annotation} public static void run() {{ result = compute(12); }}
  private static int compute(int value) {{ return value * 3 + 6; }}
  public static int unrelated(int value) {{ return value ^ 17; }}
}}''')
            classes = work / 'classes'
            run(['javac', '--release', '8', '-d', classes, *files])
            original = work / 'input.jar'
            resource = (b'{"schemaVersion":1,"id":"fixture","version":"1.0",'
                        b'"entrypoints":{"main":["fixture.ExampleMod"]}}'
                        if loader == 'fabric' else b'[[mods]]\nmodId="fixture"\nversion="1.0"\n')
            with zipfile.ZipFile(original, 'w') as jar:
                for file in sorted(classes.rglob('*.class')):
                    jar.write(file, file.relative_to(classes))
                jar.writestr(descriptor, resource)
            for prefix, platform in [([], 'auto'), (['obfuscate'], loader)]:
                output = work / f'{platform}.jar'
                report = work / f'{platform}.json'
                summary = work / f'{platform}-summary.json'
                selection = (r'fixture/Tasks\.unrelated.*' if loader == 'fabric'
                             else r'fixture/Tasks\.(run|compute|unrelated).*')
                run([CLI, *prefix, original, '-o', output, '--extension', extension,
                     '--platform', platform, '--application-package', 'fixture',
                     '--keep', 'net.**', '--no-rename-methods', '--no-rename-fields',
                     '--virtualize-method', selection, '--virtualization-mode', 'strict',
                     '--virtualization-report', report, '--report', summary,
                     '--verify-output', '--seed', '20261123'])
                assert json.loads(summary.read_text())['platform'] == loader
                methods = {entry['identity']: entry for entry in json.loads(report.read_text())['methods']}
                assert methods['fixture/Tasks.unrelated(I)I']['selected']
                if loader != 'fabric':
                    for identity, reason in [('fixture/Tasks.run()V', 'minecraft mod event handler'),
                                             ('fixture/Tasks.compute(I)I', 'minecraft mod hot callback dependency')]:
                        entry = methods[identity]
                        assert not entry['selected'] and entry['exclusionReason'] == reason, entry
                with zipfile.ZipFile(output) as jar:
                    assert jar.read(descriptor) == resource
                    assert 'fixture/ExampleMod.class' in jar.namelist()
                    assert 'fixture/Tasks.class' not in jar.namelist()
                assert run(['java', '-Xverify:all', '-cp', output, 'fixture.ExampleMod']) == f'{loader}-extension-ok'
        print(f'{loader}: provider loading, detection, policy, resources and output execution passed')


if __name__ == '__main__':
    main()
