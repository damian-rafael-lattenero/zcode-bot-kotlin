# test_deploy_lib.nu — the proofs for scripts/lib/deploy.nu, the shared core
# of the brain/bot/whatsapp deploy scripts: tail-lines (the log tail they
# print on failure) and wait-for-path (the event-driven existence wait whose
# old polling version had the measured `path type != ""` latent bug).
use std/assert
use ../lib/deploy.nu *

let tmp: path = ($"($env.TMPDIR? | default /tmp)/deploy-lib-(random uuid)")
mkdir $tmp

# 1. tail-lines: missing file answers [], short files answer everything,
#    long files answer the LAST n lines.
assert equal (tail-lines ($tmp | path join "absent.log") 5) []
let log: path = ($tmp | path join "app.log")
"l1\nl2\nl3\nl4\nl5\n" | save -f --raw $log
assert equal (tail-lines $log 2) ["l4" "l5"]
assert equal (tail-lines $log 10) ["l1" "l2" "l3" "l4" "l5"]
assert equal (tail-lines $log 0) []

# 2. wait-for-path: an existing path answers true immediately.
let present: path = ($tmp | path join "present.txt")
"x" | save -f --raw $present
let t0: datetime = (date now)
assert equal (wait-for-path $present 2000) true
assert less ((date now) - $t0) 1sec

# 3. wait-for-path: a path that APPEARS mid-wait answers true (the watch
#    fires before the deadline — the pre-watch race is covered too).
let appearing: string = ($tmp | path join "appearing.txt")
job spawn { sleep 300ms; "x" | save -f --raw $appearing } | ignore
let t1: datetime = (date now)
assert equal (wait-for-path $appearing 3000) true
assert less ((date now) - $t1) 3sec

# 4. wait-for-path: a path that never appears answers false at the deadline
#    (and really waited).
let never: string = ($tmp | path join "never.txt")
let t2: datetime = (date now)
assert equal (wait-for-path $never 1000) false
assert greater or equal ((date now) - $t2) 900ms

# 5. fresh? / armed?: the file must be NEWER than the deploy (and zconn
#    present for armed?) — the 2/11 bug was mere existence passing a daemon
#    that never booted; the whatsapp 22/11 bug was a log from a past run.
let hb: path = ($tmp | path join "hb-state")
let zconn: path = ($tmp | path join "zconn.json")
"1" | save -f --raw $hb
"{}" | save -f --raw $zconn
let mtime: int = (^stat -f %m $hb | str trim | into int)
assert equal (fresh? $hb 0) true
assert equal (fresh? $hb ($mtime + 60)) false               # stale file
assert equal (fresh? ($tmp | path join "absent") 0) false
assert equal (armed? $hb $zconn 0) true
assert equal (armed? $hb $zconn ($mtime + 60)) false        # stale heartbeat
assert equal (armed? $hb ($tmp | path join "no-zconn") 0) false
assert equal (armed? ($tmp | path join "no-hb") $zconn 0) false

# 6. wait-armed / wait-fresh: the file landing mid-wait wakes the caller; a
#    deploy that never arms/freshens answers false at the deadline.
let hb2: string = ($tmp | path join "hb-late")
let zc2: string = ($tmp | path join "zconn-late.json")
job spawn { sleep 300ms; "1" | save -f --raw $hb2; "{}" | save -f --raw $zc2 } | ignore
let t3: datetime = (date now)
assert equal (wait-armed $hb2 $zc2 0 3sec) true
assert less ((date now) - $t3) 3sec
let hb3: string = ($tmp | path join "hb-never")
let zc3: string = ($tmp | path join "zconn-never.json")
assert equal (wait-armed $hb3 $zc3 0 1sec) false

# 7. wait-fresh (the whatsapp log shape): a fresh log appears mid-wait ->
#    true; never -> false at the deadline.
let late_log: string = ($tmp | path join "daemon.log")
job spawn { sleep 300ms; "started" | save -f --raw $late_log } | ignore
assert equal (wait-fresh $late_log 0 3sec) true
assert equal (wait-fresh ($tmp | path join "never.log") 0 1sec) false

^rm -rf $tmp
print "test_deploy_lib ok"
