#!/opt/homebrew/bin/nu
# test.nu — the gate for the repo's Nushell scripts: nu-check every module
# under scripts/ and run every scripts/tests/*.nu. Same shape as
# ~/Scripts/test.nu and nushell-fp's gate; offenders are reported by name.
#
#   nu scripts/test.nu
const SELF: path = (path self)

def main []: nothing -> nothing {
    let root: path = ($SELF | path dirname)
    let scripts: list<string> = (glob ($root | path join '*.nu') | sort | where {|f| ($f | path basename) != 'test.nu'})
    let modules: list<string> = (glob ($root | path join 'lib/*.nu') | sort)
    let tests: list<string> = (glob ($root | path join 'tests/*.nu') | sort)

    let bad_modules: list<string> = (($scripts | append $modules) | where {|m| not (nu-check $m)})
    if not ($bad_modules | is-empty) {
        error make {msg: ("nu-check failed: " + ($bad_modules | str join ", "))}
    }

    let failures: list<string> = ($tests | each {|script|
        let run: record<exit_code: int, stdout: string, stderr: string> = (nu $script | complete)
        if $run.exit_code == 0 {
            print ($run.stdout | str trim)
            null
        } else {
            print ($run.stderr | str trim)
            $script
        }
    } | where {|s| $s != null})

    if ($failures | is-empty) {
        print $"ALL GREEN — ($tests | length) test scripts, ($modules | length) lib modules, ($scripts | length) scripts"
    } else {
        error make {msg: ("failing test scripts: " + ($failures | str join ", "))}
    }
}
