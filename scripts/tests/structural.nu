# structural.nu — the family's proof in the law's mode B (the scripts here are
# effects: builds, launchctl, deploys, log reads). Every script must parse, its
# shebang interpreter must resolve, and no stale pre-hub import may survive.
# Semantics belong to each script's own test.
# covers: /Users/lattenero/Projects/zcode-bot-kotlin/scripts/*.nu
use std/assert
use /Users/lattenero/Scripts/lib/structural.nu *

let files: list<string> = (glob '/Users/lattenero/Projects/zcode-bot-kotlin/scripts/*.nu' | where {|f| ($f | path basename) != 'test.nu'})
assert greater ($files | length) 10 "the glob must see the family"

let found: list<Finding> = (findings $files)
assert equal $found [] ($found | to json)

print $"bot scripts structural ok — ($files | length) scripts"
