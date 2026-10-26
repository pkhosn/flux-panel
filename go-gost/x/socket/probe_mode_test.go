package socket

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestProbeModeBlocksForwardingAndPersistsEnable(t *testing.T) {
	old, _ := os.Getwd()
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(old)
	defer SetProbeMode(false)
	SetProbeMode(true)
	for _, command := range []string{"AddService", "ReconcileForwards", "AddChain", "SetProtocol"} {
		if probeCommandAllowed(command) {
			t.Fatalf("allowed %s", command)
		}
	}
	if !probeCommandAllowed("EnableForwarding") || !probeCommandAllowed("call") {
		t.Fatal("control blocked")
	}
	if err := enableForwarding(); err == nil || !ProbeMode() {
		t.Fatal("missing config enabled forwarding")
	}
	if err := os.WriteFile("config.json", []byte(`{"addr":"panel:6365","secret":"keep","probe_mode":true,"custom":"keep"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := enableForwarding(); err != nil {
		t.Fatal(err)
	}
	if ProbeMode() || !probeCommandAllowed("AddService") {
		t.Fatal("not enabled")
	}
	raw, _ := os.ReadFile("config.json")
	var saved map[string]interface{}
	if err := json.Unmarshal(raw, &saved); err != nil {
		t.Fatal(err)
	}
	if saved["probe_mode"] != false || saved["secret"] != "keep" || saved["custom"] != "keep" {
		t.Fatal(saved)
	}
	stat, _ := os.Stat("config.json")
	if stat.Mode().Perm() != 0600 {
		t.Fatal("credential permissions")
	}
	files, _ := filepath.Glob(".flux-config-*")
	if len(files) != 0 {
		t.Fatal(files)
	}
	if err := enableForwarding(); err != nil {
		t.Fatal("retry", err)
	}
}

func TestProbeWaitsUntilForwardingEnabled(t *testing.T) {
	SetProbeMode(true)
	defer SetProbeMode(false)
	ready := make(chan struct{})
	go func() { WaitForForwarding(); close(ready) }()
	select {
	case <-ready:
		t.Fatal("forwarding started in probe mode")
	case <-time.After(150 * time.Millisecond):
	}
	SetProbeMode(false)
	select {
	case <-ready:
	case <-time.After(time.Second):
		t.Fatal("forwarding did not start")
	}
}
