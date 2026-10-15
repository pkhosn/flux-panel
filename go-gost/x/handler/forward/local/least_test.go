package local

import "testing"

func TestLeastConnectionsCountsAndReleases(t *testing.T) {
	h := &forwardHandler{}
	h.md.leastTargets = []string{"a:1", "b:2"}
	first, releaseFirst := h.acquireLeast()
	second, releaseSecond := h.acquireLeast()
	if first == second {
		t.Fatal("busy target selected over idle target")
	}
	releaseFirst()
	releaseFirst()
	third, releaseThird := h.acquireLeast()
	if third != first {
		t.Fatal("released target not preferred")
	}
	releaseSecond()
	releaseThird()
	for _, count := range h.active {
		if count != 0 {
			t.Fatal("active count leaked")
		}
	}
}
