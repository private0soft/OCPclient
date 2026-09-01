// vpnc-script-proxy.js
// Tunnel address only — system default route and DNS stay on the physical NIC.
// A high-metric 0.0.0.0/0 on the TUN lets SOCKS/HTTP sockets leave via Wintun
// when the app binds them with IP_UNICAST_IF; it is not used for general traffic.
//
// All netsh/route commands MUST wait. WshShell.Exec without ReadAll/Status is
// fire-and-forget: WScript.Quit then kills in-flight netsh on some Windows
// builds, leaving a live CSTP session with no TUN IP/route (proxy = no traffic).

var fatal = 0;
var ws = WScript.CreateObject("WScript.Shell");
var env = ws.Environment("Process");
var comspec = ws.ExpandEnvironmentStrings("%comspec%");

function echo(msg) {
    WScript.Echo(msg);
}

// Hidden window, wait until the process exits (unlike Exec + NUL).
function run_wait(cmd) {
    return ws.Run(comspec + " /C \"" + cmd + "\"", 0, true);
}

function run_capture(cmd) {
    var oExec = ws.Exec(comspec + " /C \"" + cmd + "\" 2>&1");
    oExec.StdIn.Close();
    return oExec.StdOut.ReadAll();
}

function getDefaultGateway4() {
    // IPv4-only table — full `route print` (v4+v6) is very slow on Windows.
    var out = run_capture("route print -4");
    if (out.match(/0\.0\.0\.0\s+(0|128)\.0\.0\.0\s+([0-9\.]+)/)) {
        return (RegExp.$2);
    }
    return "";
}

function addTunDefaultRoute(tunidx, gw) {
    // Leftover route from a crashed session would make add fail.
    run_wait("netsh interface ipv4 delete route prefix=0.0.0.0/0 interface=" +
        tunidx + " store=active");

    var cmds = [
        "netsh interface ipv4 add route prefix=0.0.0.0/0 interface=" + tunidx +
            " nexthop=" + gw + " metric=9000 store=active",
        // Some Windows builds reject nexthop=own-IP on Wintun; on-link works.
        "netsh interface ipv4 add route prefix=0.0.0.0/0 interface=" + tunidx +
            " nexthop=0.0.0.0 metric=9000 store=active",
        "route add 0.0.0.0 mask 0.0.0.0 " + gw + " metric 9000 if " + tunidx
    ];
    var i;
    for (i = 0; i < cmds.length; i++) {
        if (run_wait(cmds[i]) == 0) {
            echo("proxy TUN default route ok");
            return;
        }
    }
    echo("WARNING: no TUN default route — proxy sockets may send no bytes");
}

run_wait("chcp 65001");

switch (env("reason")) {
case "pre-init":
    break;
case "connect":
    var gw4 = getDefaultGateway4();
    var internal_ip4_netmask = env("INTERNAL_IP4_NETMASK") || "255.255.255.255";
    var internal_gw = env("INTERNAL_IP4_ADDRESS");
    var vpngw = env("VPNGATEWAY");
    var tunidx = env("TUNIDX");

    if (!tunidx || !internal_gw) {
        echo("ERROR: TUNIDX or INTERNAL_IP4_ADDRESS missing");
        fatal = 1;
        break;
    }

    echo("proxy connect TUNIDX=" + tunidx + " addr=" + internal_gw);

    if (vpngw && !vpngw.match(/:/g) && gw4) {
        run_wait("route add " + vpngw + " mask 255.255.255.255 " + gw4);
    }

    // High interface metric so Windows does not prefer TUN over the physical NIC.
    run_wait("netsh interface ip set interface " + tunidx + " metric=9000 store=active");

    if (run_wait("netsh interface ip set address " + tunidx + " static " +
            internal_gw + " " + internal_ip4_netmask + " store=active") != 0) {
        // Already assigned (Wintun/previous run) still leaves a usable unicast.
        echo("WARNING: netsh set address returned non-zero for " + internal_gw);
    }

    if (env("INTERNAL_IP4_MTU")) {
        run_wait("netsh interface ipv4 set subinterface " + tunidx +
            " mtu=" + env("INTERNAL_IP4_MTU") + " store=active");
    }

    // NDIS can publish the unicast row a moment after netsh returns.
    WScript.Sleep(200);
    addTunDefaultRoute(tunidx, internal_gw);
    break;
case "disconnect":
    var vpngw = env("VPNGATEWAY");
    var tunidx = env("TUNIDX");
    if (vpngw && !vpngw.match(/:/g)) {
        run_wait("route delete " + vpngw + " mask 255.255.255.255");
    }
    if (tunidx) {
        run_wait("netsh interface ipv4 delete route prefix=0.0.0.0/0 interface=" +
            tunidx + " store=active");
    }
    if (env("INTERNAL_IP4_ADDRESS") && tunidx) {
        run_wait("netsh interface ipv4 delete address " + tunidx + " " +
            env("INTERNAL_IP4_ADDRESS") + " gateway=all");
    }
    break;
}

WScript.Quit(fatal);
