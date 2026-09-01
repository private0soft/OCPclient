//! Local SOCKS5 + HTTP CONNECT. Outbound sockets are forced out the TUN
//! interface (IP_UNICAST_IF) so they ride the VPN without a system default route.

use std::io::{Read, Write};
use std::net::{
    IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs,
};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub const SOCKS_ADDR: &str = "127.0.0.1:1080";
pub const HTTP_ADDR: &str = "127.0.0.1:8118";

pub type LogFn = Arc<dyn Fn(&str) + Send + Sync>;

pub fn spawn(bind_ip: Ipv4Addr, shutdown: Arc<AtomicBool>, log: LogFn) -> Result<(), String> {
    // Claim ports first so the UI can advertise the proxy while TUN ifindex settles.
    let socks = bind_listener(SOCKS_ADDR)?;
    let http = bind_listener(HTTP_ADDR)?;

    let ifindex = Arc::new(AtomicU32::new(0));
    if let Some(idx) = wait_ifindex(bind_ip, 20) {
        ifindex.store(idx, Ordering::SeqCst);
        log(&format!("proxy TUN ifindex {idx} for {bind_ip}"));
    } else {
        log(&format!(
            "proxy TUN ifindex not ready for {bind_ip} — retrying in background"
        ));
        spawn_ifindex_refresh(
            bind_ip,
            Arc::clone(&ifindex),
            Arc::clone(&shutdown),
            Arc::clone(&log),
        );
    }

    let s1 = Arc::clone(&shutdown);
    let i1 = Arc::clone(&ifindex);
    let l1 = Arc::clone(&log);
    thread::spawn(move || accept_loop(socks, bind_ip, i1, s1, l1, handle_socks));
    let s2 = Arc::clone(&shutdown);
    let i2 = Arc::clone(&ifindex);
    let l2 = Arc::clone(&log);
    thread::spawn(move || accept_loop(http, bind_ip, i2, s2, l2, handle_http_connect));
    Ok(())
}

pub fn stop(shutdown: &AtomicBool) {
    shutdown.store(true, Ordering::SeqCst);
    let _ = TcpStream::connect_timeout(&SOCKS_ADDR.parse().unwrap(), Duration::from_millis(200));
    let _ = TcpStream::connect_timeout(&HTTP_ADDR.parse().unwrap(), Duration::from_millis(200));
}

fn spawn_ifindex_refresh(
    bind_ip: Ipv4Addr,
    ifindex: Arc<AtomicU32>,
    shutdown: Arc<AtomicBool>,
    log: LogFn,
) {
    thread::spawn(move || {
        for _ in 0..50 {
            if shutdown.load(Ordering::SeqCst) {
                return;
            }
            if let Some(idx) = ifindex_for_ipv4(bind_ip) {
                ifindex.store(idx, Ordering::SeqCst);
                log(&format!("proxy TUN ifindex {idx} for {bind_ip} (late)"));
                return;
            }
            thread::sleep(Duration::from_millis(200));
        }
        log(&format!(
            "proxy TUN ifindex still missing for {bind_ip} — outbound may fail"
        ));
    });
}

fn bind_listener(addr: &str) -> Result<TcpListener, String> {
    let listener = TcpListener::bind(addr).map_err(|e| format!("{addr}: {e}"))?;
    listener
        .set_nonblocking(false)
        .map_err(|e| format!("{addr}: {e}"))?;
    Ok(listener)
}

fn accept_loop(
    listener: TcpListener,
    bind_ip: Ipv4Addr,
    ifindex: Arc<AtomicU32>,
    shutdown: Arc<AtomicBool>,
    log: LogFn,
    handle: fn(TcpStream, Ipv4Addr, Option<u32>) -> Result<(), String>,
) {
    for incoming in listener.incoming() {
        if shutdown.load(Ordering::SeqCst) {
            break;
        }
        let Ok(stream) = incoming else {
            continue;
        };
        let flag = Arc::clone(&shutdown);
        let log = Arc::clone(&log);
        let idx_now = ifindex.load(Ordering::SeqCst);
        let idx = if idx_now == 0 {
            ifindex_for_ipv4(bind_ip)
        } else {
            Some(idx_now)
        };
        if let Some(found) = idx {
            if idx_now == 0 {
                ifindex.store(found, Ordering::SeqCst);
            }
        }
        thread::spawn(move || {
            if flag.load(Ordering::SeqCst) {
                return;
            }
            if let Err(e) = handle(stream, bind_ip, idx) {
                if !is_client_noise(&e) {
                    log(&format!("proxy: {e}"));
                }
            }
        });
    }
}

fn handle_socks(mut client: TcpStream, bind_ip: Ipv4Addr, ifindex: Option<u32>) -> Result<(), String> {
    client.set_read_timeout(Some(Duration::from_secs(30))).ok();
    client.set_nodelay(true).ok();
    let mut hdr = [0u8; 2];
    client.read_exact(&mut hdr).map_err(|e| e.to_string())?;
    if hdr[0] != 5 {
        return Err("not socks5".into());
    }
    let nmethods = hdr[1] as usize;
    let mut methods = vec![0u8; nmethods];
    client.read_exact(&mut methods).map_err(|e| e.to_string())?;
    client.write_all(&[5, 0]).map_err(|e| e.to_string())?;

    let mut req = [0u8; 4];
    client.read_exact(&mut req).map_err(|e| e.to_string())?;
    if req[0] != 5 || req[1] != 1 {
        let _ = client.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]);
        return Err("only CONNECT is supported".into());
    }
    let dest = read_socks_dest(&mut client, req[3])?;
    match connect_via_vpn(dest, bind_ip, ifindex) {
        Ok(remote) => {
            let _ = client.write_all(&[5, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
            splice(client, remote);
            Ok(())
        }
        Err(e) => {
            let _ = client.write_all(&[5, 5, 0, 1, 0, 0, 0, 0, 0, 0]);
            Err(format!("socks {dest} → {e}"))
        }
    }
}

fn read_socks_dest(client: &mut TcpStream, atyp: u8) -> Result<SocketAddr, String> {
    match atyp {
        1 => {
            let mut buf = [0u8; 6];
            client.read_exact(&mut buf).map_err(|e| e.to_string())?;
            let ip = Ipv4Addr::new(buf[0], buf[1], buf[2], buf[3]);
            let port = u16::from_be_bytes([buf[4], buf[5]]);
            Ok(SocketAddr::new(IpAddr::V4(ip), port))
        }
        3 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len).map_err(|e| e.to_string())?;
            let mut host = vec![0u8; len[0] as usize];
            client.read_exact(&mut host).map_err(|e| e.to_string())?;
            let mut portb = [0u8; 2];
            client.read_exact(&mut portb).map_err(|e| e.to_string())?;
            let port = u16::from_be_bytes(portb);
            let name = String::from_utf8_lossy(&host);
            resolve(&format!("{name}:{port}"))
        }
        4 => {
            let mut buf = [0u8; 18];
            client.read_exact(&mut buf).map_err(|e| e.to_string())?;
            let mut oct = [0u8; 16];
            oct.copy_from_slice(&buf[..16]);
            let port = u16::from_be_bytes([buf[16], buf[17]]);
            Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(oct)), port))
        }
        _ => Err("bad atyp".into()),
    }
}

fn handle_http_connect(
    mut client: TcpStream,
    bind_ip: Ipv4Addr,
    ifindex: Option<u32>,
) -> Result<(), String> {
    client.set_read_timeout(Some(Duration::from_secs(30))).ok();
    client.set_nodelay(true).ok();
    let mut buf = Vec::new();
    let mut tmp = [0u8; 512];
    loop {
        let n = client.read(&mut tmp).map_err(|e| e.to_string())?;
        if n == 0 {
            return Err("eof".into());
        }
        buf.extend_from_slice(&tmp[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
        if buf.len() > 8192 {
            return Err("header too large".into());
        }
    }
    let text = String::from_utf8_lossy(&buf);
    let first = text.lines().next().unwrap_or("");
    let dest = parse_connect_line(first)?;
    match connect_via_vpn(dest, bind_ip, ifindex) {
        Ok(remote) => {
            let _ = client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n");
            splice(client, remote);
            Ok(())
        }
        Err(e) => {
            let _ = client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
            Err(format!("http {dest} → {e}"))
        }
    }
}

fn parse_connect_line(line: &str) -> Result<SocketAddr, String> {
    let mut parts = line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let target = parts.next().unwrap_or("");
    if !method.eq_ignore_ascii_case("CONNECT") {
        return Err("only CONNECT".into());
    }
    resolve(target)
}

fn resolve(hostport: &str) -> Result<SocketAddr, String> {
    hostport
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .find(|a| a.is_ipv4())
        .ok_or_else(|| "dns failed".into())
}

fn connect_via_vpn(
    dest: SocketAddr,
    bind_ip: Ipv4Addr,
    ifindex: Option<u32>,
) -> Result<TcpStream, String> {
    let dest_v4 = match dest {
        SocketAddr::V4(a) => SocketAddr::V4(a),
        SocketAddr::V6(_) => return Err("ipv6 dest not supported in proxy mode".into()),
    };
    // Address can appear a few hundred ms after netsh; bind 10049 is retryable.
    let mut last = "connect failed".to_string();
    for attempt in 0..8 {
        match connect_once(dest_v4, bind_ip, ifindex) {
            Ok(s) => return Ok(s),
            Err(e) => {
                let retry = attempt < 7 && is_retryable_bind(&e);
                last = e;
                if !retry {
                    break;
                }
                thread::sleep(Duration::from_millis(100));
            }
        }
    }
    Err(last)
}

fn is_retryable_bind(err: &str) -> bool {
    // TUN unicast / route can lag netsh by a few hundred ms on some PCs.
    err.contains("10049")
        || err.contains("10051")
        || err.contains("10065")
        || err.contains("Can't assign")
        || err.contains("address not available")
        || err.contains("unreachable")
}

fn is_client_noise(err: &str) -> bool {
    err == "eof"
        || err == "not socks5"
        || err.contains("fill whole buffer")
        || err.contains("early eof")
}

fn connect_once(
    dest_v4: SocketAddr,
    bind_ip: Ipv4Addr,
    ifindex: Option<u32>,
) -> Result<TcpStream, String> {
    let idx = ifindex_for_ipv4(bind_ip).or(ifindex);
    let socket = socket2::Socket::new(
        socket2::Domain::IPV4,
        socket2::Type::STREAM,
        Some(socket2::Protocol::TCP),
    )
    .map_err(|e| e.to_string())?;
    socket.set_nodelay(true).ok();

    // Never connect on 0.0.0.0 unless IP_UNICAST_IF actually stuck — otherwise
    // Windows uses the physical default route and the VPN sees zero inner bytes.
    let unicast_ok = match idx {
        Some(i) => set_unicast_if(&socket, i).is_ok(),
        None => false,
    };

    let bind_addr = SocketAddr::new(IpAddr::V4(bind_ip), 0);
    if let Err(e) = socket.bind(&socket2::SockAddr::from(bind_addr)) {
        if unicast_ok {
            socket
                .bind(&socket2::SockAddr::from(SocketAddr::new(
                    IpAddr::V4(Ipv4Addr::UNSPECIFIED),
                    0,
                )))
                .map_err(|e2| format!("bind {bind_ip} ({e}); fallback 0.0.0.0: {e2}"))?;
        } else {
            return Err(format!("bind {bind_ip}: {e}"));
        }
    }
    socket
        .connect_timeout(&socket2::SockAddr::from(dest_v4), Duration::from_secs(20))
        .map_err(|e| format!("{e}"))?;
    Ok(socket.into())
}

fn set_unicast_if(socket: &socket2::Socket, ifindex: u32) -> Result<(), String> {
    use std::os::windows::io::AsRawSocket;
    const IPPROTO_IP: i32 = 0;
    const IP_UNICAST_IF: i32 = 31;
    // IPv4 IP_UNICAST_IF wants the IfIndex in network byte order (htonl).
    let val = ifindex.to_be();
    let rc = unsafe {
        setsockopt(
            socket.as_raw_socket() as usize,
            IPPROTO_IP,
            IP_UNICAST_IF,
            &val as *const u32 as *const u8,
            4,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(format!(
            "IP_UNICAST_IF {ifindex} failed wsa={}",
            unsafe { WSAGetLastError() }
        ))
    }
}

fn wait_ifindex(ip: Ipv4Addr, attempts: u32) -> Option<u32> {
    for _ in 0..attempts {
        if let Some(idx) = ifindex_for_ipv4(ip) {
            return Some(idx);
        }
        thread::sleep(Duration::from_millis(100));
    }
    None
}

fn ifindex_for_ipv4(ip: Ipv4Addr) -> Option<u32> {
    adapters_ifindex(ip).or_else(|| ipaddr_table_ifindex(ip))
}

/// Prefix of IP_ADAPTER_ADDRESSES — we only read fields whose offsets are
/// stable since XP SP1. The OS fills the real (larger) struct in the buffer.
#[repr(C)]
struct IpAdapterAddressesPrefix {
    length: u32,
    if_index: u32,
    next: *mut IpAdapterAddressesPrefix,
    adapter_name: *mut i8,
    first_unicast: *mut IpAdapterUnicastPrefix,
}

#[repr(C)]
struct IpAdapterUnicastPrefix {
    length: u32,
    flags: u32,
    next: *mut IpAdapterUnicastPrefix,
    address: SocketAddress,
}

#[repr(C)]
struct SocketAddress {
    lp_sockaddr: *const SockAddrIn,
    i_sockaddr_length: i32,
}

#[repr(C)]
struct SockAddrIn {
    sin_family: i16,
    sin_port: u16,
    sin_addr: [u8; 4],
    sin_zero: [u8; 8],
}

const AF_INET: u32 = 2;
const GAA_SKIP: u32 = 0x0002 | 0x0004 | 0x0008 | 0x0020; // anycast/mcast/dns/friendly
const ERROR_BUFFER_OVERFLOW: u32 = 111;

fn adapters_ifindex(ip: Ipv4Addr) -> Option<u32> {
    let mut size: u32 = 0;
    unsafe {
        GetAdaptersAddresses(AF_INET, GAA_SKIP, std::ptr::null_mut(), std::ptr::null_mut(), &mut size);
    }
    if size < 64 {
        return None;
    }
    for _ in 0..3 {
        let mut buf = vec![0u8; size as usize];
        let err = unsafe {
            GetAdaptersAddresses(
                AF_INET,
                GAA_SKIP,
                std::ptr::null_mut(),
                buf.as_mut_ptr(),
                &mut size,
            )
        };
        if err == ERROR_BUFFER_OVERFLOW {
            continue;
        }
        if err != 0 {
            return None;
        }
        let mut p = buf.as_mut_ptr() as *mut IpAdapterAddressesPrefix;
        while !p.is_null() {
            let adapter = unsafe { &*p };
            let mut u = adapter.first_unicast;
            while !u.is_null() {
                let uni = unsafe { &*u };
                if let Some(addr) = sockaddr_v4(uni.address.lp_sockaddr) {
                    if addr == ip && adapter.if_index != 0 {
                        return Some(adapter.if_index);
                    }
                }
                u = uni.next;
            }
            p = adapter.next;
        }
        return None;
    }
    None
}

fn sockaddr_v4(p: *const SockAddrIn) -> Option<Ipv4Addr> {
    if p.is_null() {
        return None;
    }
    let sa = unsafe { &*p };
    if sa.sin_family != 2 {
        return None;
    }
    Some(Ipv4Addr::new(
        sa.sin_addr[0],
        sa.sin_addr[1],
        sa.sin_addr[2],
        sa.sin_addr[3],
    ))
}

const ERROR_INSUFFICIENT_BUFFER: u32 = 122;

fn ipaddr_table_ifindex(ip: Ipv4Addr) -> Option<u32> {
    let mut size: u32 = 0;
    unsafe {
        GetIpAddrTable(std::ptr::null_mut(), &mut size, 0);
    }
    if size < 4 {
        return None;
    }
    for _ in 0..3 {
        let mut buf = vec![0u8; size as usize];
        let err = unsafe { GetIpAddrTable(buf.as_mut_ptr(), &mut size, 0) };
        if err == ERROR_INSUFFICIENT_BUFFER {
            continue;
        }
        if err != 0 {
            return None;
        }
        let count = u32::from_le_bytes(buf[0..4].try_into().ok()?);
        let row_size = std::mem::size_of::<MibIpAddrRow>();
        for i in 0..count as usize {
            let off = 4 + i * row_size;
            if off + row_size > buf.len() {
                break;
            }
            let row = unsafe { std::ptr::read_unaligned(buf[off..].as_ptr() as *const MibIpAddrRow) };
            // dwAddr is in_addr / network-order bytes.
            let addr = Ipv4Addr::from(row.dw_addr.to_ne_bytes());
            if addr == ip {
                return Some(row.dw_index);
            }
        }
        return None;
    }
    None
}

fn splice(mut a: TcpStream, mut b: TcpStream) {
    let mut a2 = match a.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let mut b2 = match b.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    a.set_nodelay(true).ok();
    b.set_nodelay(true).ok();
    thread::spawn(move || {
        let _ = std::io::copy(&mut a2, &mut b2);
        let _ = b2.shutdown(Shutdown::Write);
    });
    let _ = std::io::copy(&mut b, &mut a);
    let _ = a.shutdown(Shutdown::Write);
}

#[repr(C)]
struct MibIpAddrRow {
    dw_addr: u32,
    dw_index: u32,
    dw_mask: u32,
    dw_bcast: u32,
    dw_reasm: u32,
    unused1: u16,
    w_type: u16,
}

#[link(name = "iphlpapi")]
extern "system" {
    fn GetIpAddrTable(table: *mut u8, size: *mut u32, order: i32) -> u32;
    fn GetAdaptersAddresses(
        family: u32,
        flags: u32,
        reserved: *mut u8,
        adapter: *mut u8,
        size: *mut u32,
    ) -> u32;
}

#[link(name = "ws2_32")]
extern "system" {
    fn setsockopt(s: usize, level: i32, optname: i32, optval: *const u8, optlen: i32) -> i32;
    fn WSAGetLastError() -> i32;
}
