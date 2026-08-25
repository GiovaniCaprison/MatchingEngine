#!/bin/sh
# Prepares a bare metal Linux box the way METHODOLOGY's environment section asks, and checks that
# every setting took. The harness re-verifies all of this from the kernel's own files at run time
# and grades the run on what it finds, so this script existing is convenience and not evidence.
#
# Usage, on the instance (c6i.metal or similar), as root:
#
#   metal_setup.sh boot ISOLATED        write kernel parameters for the isolated cores, then reboot
#   metal_setup.sh runtime ISOLATED     the settings that do not need a reboot, after it
#   metal_setup.sh check                print every setting the harness will read
#
# where ISOLATED is a cpu list like 2-5. The engine pins to one of these; the driver and verifier
# want cores outside the list, near it. A typical session:
#
#   ./metal_setup.sh boot 2-5 && reboot
#   ./metal_setup.sh runtime 2-5
#   ./metal_setup.sh check
#   python3 scripts/matrix.py --jar benchmarks.jar --results results/session-01 --cores 6,2,8 ...

set -eu

MODE="${1:-check}"
ISOLATED="${2:-}"

boot() {
  # Deep C-state exit latency lands in the tail, and a core the kernel schedules on is a core
  # whose cache belongs to somebody else. All of these are boot parameters.
  PARAMETERS="isolcpus=${ISOLATED} nohz_full=${ISOLATED} rcu_nocbs=${ISOLATED}"
  PARAMETERS="${PARAMETERS} processor.max_cstate=1 intel_idle.max_cstate=0"
  echo "adding to GRUB_CMDLINE_LINUX_DEFAULT: ${PARAMETERS}"
  sed -i "s/^GRUB_CMDLINE_LINUX_DEFAULT=\"\([^\"]*\)\"/GRUB_CMDLINE_LINUX_DEFAULT=\"\1 ${PARAMETERS}\"/" /etc/default/grub
  update-grub
  echo "now reboot, then run: $0 runtime ${ISOLATED}"
}

runtime() {
  # Frequency held still, counters and symbols readable, swap off, and the isolated cores'
  # hyperthread siblings taken offline so nothing shares their pipelines.
  for governor in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    echo performance > "${governor}"
  done
  if [ -w /sys/devices/system/cpu/intel_pstate/no_turbo ]; then
    echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo
  fi
  sysctl -w kernel.perf_event_paranoid=1
  sysctl -w kernel.kptr_restrict=0
  swapoff -a
  for cpu in $(expand_list "${ISOLATED}"); do
    siblings="/sys/devices/system/cpu/cpu${cpu}/topology/thread_siblings_list"
    if [ -r "${siblings}" ]; then
      for sibling in $(expand_list "$(cat "${siblings}")"); do
        if [ "${sibling}" != "${cpu}" ]; then
          echo "taking cpu${sibling} offline, sibling of cpu${cpu}"
          echo 0 > "/sys/devices/system/cpu/cpu${sibling}/online"
        fi
      done
    fi
  done
  echo "done. verify with: $0 check"
}

check() {
  echo "cmdline:            $(cat /proc/cmdline)"
  echo "governor (cpu0):    $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unavailable)"
  echo "no_turbo:           $(cat /sys/devices/system/cpu/intel_pstate/no_turbo 2>/dev/null || echo unavailable)"
  echo "clocksource:        $(cat /sys/devices/system/clocksource/clocksource0/current_clocksource 2>/dev/null || echo unavailable)"
  echo "perf_event_paranoid:$(cat /proc/sys/kernel/perf_event_paranoid)"
  echo "kptr_restrict:      $(cat /proc/sys/kernel/kptr_restrict)"
  echo "swap:               $(grep SwapTotal /proc/meminfo)"
  echo "offline cpus:       $(cat /sys/devices/system/cpu/offline 2>/dev/null || echo none)"
}

expand_list() {
  # A kernel cpu list like 2-5,8 as one number per line.
  echo "$1" | tr ',' '\n' | while read -r part; do
    case "${part}" in
      *-*) seq "${part%-*}" "${part#*-}" ;;
      *) echo "${part}" ;;
    esac
  done
}

case "${MODE}" in
  boot) boot ;;
  runtime) runtime ;;
  check) check ;;
  *) echo "usage: $0 boot|runtime|check [isolated cpu list]" >&2; exit 2 ;;
esac
