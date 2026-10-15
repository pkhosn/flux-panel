package tcp

import (
	"bytes"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

var sharedPorts = struct {
	sync.Mutex
	hubs map[string]*sniHub
}{hubs: make(map[string]*sniHub)}

type sniHub struct {
	listener net.Listener
	routes   map[string]*sniListener
	slots    chan struct{}
}

type sniListener struct {
	hub    *sniHub
	key    string
	names  []string
	queue  chan net.Conn
	closed chan struct{}
	once   sync.Once
}

func normalizeSNI(raw string) (string, error) {
	name := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(raw), "."))
	if name == "" || len(name) > 253 || net.ParseIP(name) != nil {
		return "", fmt.Errorf("invalid SNI domain")
	}
	for _, label := range strings.Split(name, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return "", fmt.Errorf("invalid SNI domain")
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return "", fmt.Errorf("SNI requires ASCII domain")
			}
		}
	}
	return name, nil
}

// Every rule keeps its own wrappers and service, while only classification shares a socket.
func listenSharedSNI(network, address string, names []string) (net.Listener, error) {
	if len(names) < 1 || len(names) > 32 {
		return nil, fmt.Errorf("SNI requires 1-32 domains")
	}
	unique := make(map[string]bool)
	normalized := make([]string, 0, len(names))
	for _, value := range names {
		name, err := normalizeSNI(value)
		if err != nil {
			return nil, err
		}
		if !unique[name] {
			unique[name] = true
			normalized = append(normalized, name)
		}
	}
	sharedPorts.Lock()
	defer sharedPorts.Unlock()
	key := network + "|" + address
	hub := sharedPorts.hubs[key]
	if hub == nil {
		ln, err := net.Listen(network, address)
		if err != nil {
			return nil, err
		}
		hub = &sniHub{listener: ln, routes: make(map[string]*sniListener), slots: make(chan struct{}, 128)}
		sharedPorts.hubs[key] = hub
		go hub.serve()
	}
	for _, name := range normalized {
		if hub.routes[name] != nil {
			return nil, fmt.Errorf("SNI %s already registered", name)
		}
	}
	sub := &sniListener{hub: hub, key: key, names: normalized, queue: make(chan net.Conn, 32), closed: make(chan struct{})}
	for _, name := range normalized {
		hub.routes[name] = sub
	}
	return sub, nil
}

func (h *sniHub) serve() {
	for {
		conn, err := h.listener.Accept()
		if err != nil {
			return
		}
		select {
		case h.slots <- struct{}{}:
			go h.classify(conn)
		default:
			conn.Close()
		}
	}
}

func (h *sniHub) classify(conn net.Conn) {
	defer func() { <-h.slots }()
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	name, prefix, err := ReadClientHello(conn)
	if err != nil || name == "" {
		conn.Close()
		return
	}
	conn.SetReadDeadline(time.Time{})
	replay := &helloConn{Conn: conn, reader: io.MultiReader(bytes.NewReader(prefix), conn)}
	sharedPorts.Lock()
	defer sharedPorts.Unlock()
	sub := h.routes[name]
	if sub == nil {
		conn.Close()
		return
	}
	select {
	case sub.queue <- replay:
	default:
		conn.Close()
	}
}

type helloConn struct {
	net.Conn
	reader io.Reader
}

func (c *helloConn) Read(p []byte) (int, error) { return c.reader.Read(p) }
func (l *sniListener) Addr() net.Addr           { return l.hub.listener.Addr() }
func (l *sniListener) Accept() (net.Conn, error) {
	select {
	case <-l.closed:
		return nil, net.ErrClosed
	default:
	}
	select {
	case <-l.closed:
		return nil, net.ErrClosed
	case c := <-l.queue:
		return c, nil
	}
}
func (l *sniListener) Close() error {
	l.once.Do(func() {
		sharedPorts.Lock()
		defer sharedPorts.Unlock()
		close(l.closed)
		for _, name := range l.names {
			if l.hub.routes[name] == l {
				delete(l.hub.routes, name)
			}
		}
		for {
			select {
			case c := <-l.queue:
				c.Close()
			default:
				goto drained
			}
		}
	drained:
		if len(l.hub.routes) == 0 {
			l.hub.listener.Close()
			delete(sharedPorts.hubs, l.key)
		}
	})
	return nil
}
