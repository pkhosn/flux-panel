package socket

import (
	"encoding/json"
	"fmt"
	"os"
	"sync/atomic"
	"time"
)

var probeOnly atomic.Bool

func SetProbeMode(enabled bool) { probeOnly.Store(enabled) }
func ProbeMode() bool           { return probeOnly.Load() }

// Wait for the authenticated panel command before starting any forwarding services.
func WaitForForwarding() {
	for ProbeMode() {
		time.Sleep(100 * time.Millisecond)
	}
}

func persistForwardingMode() error {
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
	values["probe_mode"] = false
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
	if !ProbeMode() {
		return nil
	}
	if err := persistForwardingMode(); err != nil {
		return fmt.Errorf("save forwarding mode: %w", err)
	}
	SetProbeMode(false)
	return nil
}

func probeCommandAllowed(command string) bool {
	return !ProbeMode() || command == "EnableForwarding" || command == "TcpPing" || command == "call"
}
