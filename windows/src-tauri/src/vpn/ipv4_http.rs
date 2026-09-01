//! IPv4-only HTTPS GET for geo lookups.
//!
//! After a full tunnel, Windows DNS often returns AAAA first; connecting to a
//! broken IPv6 path stalls for ~20s and geo/flag never appear. Proxy mode
//! already forces IPv4 in our CONNECT resolver — this mirrors that for tunnel.

use native_tls::TlsConnector;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

pub fn https_get(url: &str, timeout: Duration) -> Result<Vec<u8>, String> {
    let (host, port, path) = parse_https_url(url)?;
    let addr = resolve_ipv4(&host, port)?;
    let tcp = TcpStream::connect_timeout(&addr, timeout).map_err(|e| e.to_string())?;
    tcp.set_read_timeout(Some(timeout)).ok();
    tcp.set_write_timeout(Some(timeout)).ok();

    let connector = TlsConnector::builder()
        .build()
        .map_err(|e| format!("tls: {e}"))?;
    let mut tls = connector
        .connect(&host, tcp)
        .map_err(|e| format!("tls handshake {host}: {e}"))?;

    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: {host}\r\nUser-Agent: OpenConnect-PlusP-Windows\r\nConnection: close\r\nAccept: */*\r\n\r\n"
    );
    tls.write_all(req.as_bytes())
        .map_err(|e| format!("write: {e}"))?;

    let mut buf = Vec::new();
    tls.read_to_end(&mut buf).map_err(|e| format!("read: {e}"))?;
    split_http_body(&buf)
}

fn parse_https_url(url: &str) -> Result<(String, u16, String), String> {
    let rest = url
        .strip_prefix("https://")
        .ok_or_else(|| format!("only https supported: {url}"))?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = if let Some(h) = authority.strip_prefix('[') {
        // rare IPv6 literal — unsupported here
        let _ = h;
        return Err("ipv6 literal urls not supported".into());
    } else if let Some((h, p)) = authority.rsplit_once(':') {
        let port: u16 = p.parse().map_err(|_| format!("bad port in {url}"))?;
        (h.to_string(), port)
    } else {
        (authority.to_string(), 443)
    };
    if host.is_empty() {
        return Err("empty host".into());
    }
    Ok((host, port, path.to_string()))
}

fn resolve_ipv4(host: &str, port: u16) -> Result<SocketAddr, String> {
    (host, port)
        .to_socket_addrs()
        .map_err(|e| format!("dns {host}: {e}"))?
        .find(|a| a.is_ipv4())
        .ok_or_else(|| format!("no IPv4 (A) for {host}"))
}

fn split_http_body(raw: &[u8]) -> Result<Vec<u8>, String> {
    let sep = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| "bad http response".to_string())?;
    let head = std::str::from_utf8(&raw[..sep]).unwrap_or("");
    let status = head.lines().next().unwrap_or("");
    if !(status.contains(" 200 ") || status.ends_with(" 200") || status.contains(" 200\r")) {
        // "HTTP/1.1 200 OK"
        let code_ok = status.split_whitespace().nth(1) == Some("200");
        if !code_ok {
            return Err(format!("http {status}"));
        }
    }
    let body = &raw[sep + 4..];
    let head_l = head.to_ascii_lowercase();
    if head_l.contains("transfer-encoding: chunked") {
        return decode_chunked(body);
    }
    if let Some(line) = head_l.lines().find(|l| l.starts_with("content-length:")) {
        if let Some(n) = line.split(':').nth(1) {
            if let Ok(len) = n.trim().parse::<usize>() {
                if body.len() >= len {
                    return Ok(body[..len].to_vec());
                }
            }
        }
    }
    Ok(body.to_vec())
}

fn decode_chunked(mut input: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    loop {
        let line_end = input
            .windows(2)
            .position(|w| w == b"\r\n")
            .ok_or_else(|| "bad chunk size".to_string())?;
        let size_str = std::str::from_utf8(&input[..line_end])
            .map_err(|_| "bad chunk size utf8".to_string())?;
        let size = usize::from_str_radix(size_str.trim(), 16)
            .map_err(|_| format!("bad chunk size '{size_str}'"))?;
        input = &input[line_end + 2..];
        if size == 0 {
            break;
        }
        if input.len() < size + 2 {
            return Err("truncated chunk".into());
        }
        out.extend_from_slice(&input[..size]);
        input = &input[size..];
        if input.starts_with(b"\r\n") {
            input = &input[2..];
        }
    }
    Ok(out)
}
