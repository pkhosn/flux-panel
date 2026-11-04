package socket

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

var probeOnly atomic.Bool
var forwardingReady atomic.Bool
var modeMutex sync.Mutex
var startForwarding func() error
var stopForwarding func() error

func SetForwardingLifecycle(start, stop func() error) {
	startForwarding, stopForwarding = start, stop
}

func SetForwardingReady(ready bool) { forwardingReady.Store(ready) }

func SetProbeMode(enabled bool) { probeOnly.Store(enabled) }
func ProbeMode() bool           { return probeOnly.Load() }

// Wait for the authenticated panel command before starting any forwarding services.
func WaitForForwarding() {
	for ProbeMode() {
		time.Sleep(100 * time.Millisecond)
	}
}

func persistProbeMode(probe bool) error {
	raw, err := os.ReadFile("config.json")
	if err != nil {
		return err
	}
	var values map[string]interface{}
	if err = json.Unmarshal(raw, &values); err != nil {
		return err
	}
	if values == nil {
		return fmt.Errorf("invalid empty agent config")
	}
	values["probe_mode"] = probe
	raw, err = json.MarshalIndent(values, "", "  ")
	if err != nil {
		return err
	}
	file, err := os.CreateTemp(".", ".flux-config-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if err = file.Chmod(0600); err == nil {
		_, err = file.Write(raw)
	}
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(file.Name(), "config.json")
}

func enableForwarding() error {
	modeMutex.Lock()
	defer modeMutex.Unlock()
	if !ProbeMode() {
		return nil
	}
	if err := persistProbeMode(false); err != nil {
		return fmt.Errorf("save forwarding mode: %w", err)
	}
	SetProbeMode(false)
	if startForwarding != nil {
		if err := startForwarding(); err != nil {
			SetProbeMode(true)
			_ = persistProbeMode(true)
			return err
		}
	}
	return nil
}

func disableForwarding() error {
	modeMutex.Lock()
	defer modeMutex.Unlock()
	if stopForwarding == nil {
		return fmt.Errorf("forwarding lifecycle unavailable")
	}
	if err := persistProbeMode(true); err != nil {
		return err
	}
	SetProbeMode(true)
	if err := stopForwarding(); err != nil {
		return err
	}
	SetForwardingReady(false)
	return nil
}

func probeCommandAllowed(command string) bool {
	return !ProbeMode() || command == "ForwardingStatus" || command == "EnableForwarding" || command == "DisableForwarding" || command == "TcpPing" || command == "call"
}
