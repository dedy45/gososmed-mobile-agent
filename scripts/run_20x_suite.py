import subprocess
import time
import json
import sys

def run_cmd(cmd):
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip(), res.stderr.strip()

def get_mem():
    out, _ = run_cmd("adb shell \"dumpsys meminfo com.gososmed.agent | grep -E 'TOTAL PSS|Native Heap:|Java Heap:'\"")
    lines = [line.strip() for line in out.splitlines() if line.strip()]
    return lines

def run_annotated(test_num, context_name, scale=0.6, quality=75):
    # clear result file
    run_cmd("adb shell \"run-as com.gososmed.agent rm -f files/agent_result.txt\"")
    start = time.time()
    
    # Broadcast command
    cmd = f'adb shell am broadcast -n com.gososmed.agent/.AgentReceiver -a com.gososmed.agent.CMD --es cmd annotatedScreenshot --es scale {scale} --ei quality {quality}'
    run_cmd(cmd)
    
    # Wait for result file
    res_data = None
    elapsed = 0
    for _ in range(40):
        time.sleep(0.1)
        out, _ = run_cmd("adb shell \"run-as com.gososmed.agent cat files/agent_result.txt 2>/dev/null\"")
        if out and "annotatedScreenshot =>" in out:
            elapsed = int((time.time() - start) * 1000)
            json_str = out.replace("annotatedScreenshot =>", "").strip()
            try:
                res_data = json.loads(json_str)
                break
            except Exception as e:
                pass
                
    if res_data is None:
        elapsed = int((time.time() - start) * 1000)
        return {
            "test": test_num,
            "context": context_name,
            "ok": False,
            "error": "timed out waiting for agent_result.txt",
            "elapsed_ms": elapsed
        }
        
    ok = res_data.get("ok", False)
    err = res_data.get("error", None)
    result = res_data.get("result", {})
    elements_count = len(result.get("elements", []))
    b64_len = len(result.get("image_base64", ""))
    
    return {
        "test": test_num,
        "context": context_name,
        "ok": ok,
        "error": err,
        "elements_count": elements_count,
        "image_bytes_approx": int(b64_len * 3 / 4),
        "elapsed_ms": elapsed
    }

def main():
    print("=== STARTING 20x ANNOTATED SCREENSHOT STABILIZATION TEST ===")
    print("Baseline Memory:")
    for line in get_mem():
        print(" ", line)
    print()
    
    results = []
    
    # Context 1: Home / Launcher (1-4)
    print("--- Phase 1: Launcher / Home Screen (Tests 1-4) ---")
    run_cmd("adb shell input keyevent 3")
    time.sleep(1)
    for i in range(1, 5):
        r = run_annotated(i, "Home Launcher")
        results.append(r)
        print(f"[{i:02d}/20] {r['context']}: ok={r['ok']} in {r['elapsed_ms']}ms, elements={r.get('elements_count', 0)}, img_size={r.get('image_bytes_approx', 0)}B (err={r.get('error')})")
        time.sleep(0.3)
        
    # Context 2: Immediate post Back/Home transitions (5-8)
    print("\n--- Phase 2: Immediate Post-Back / Post-Home Transitions (Tests 5-8) ---")
    for i in range(5, 9):
        # alternate back and home right before calling
        if i % 2 == 1:
            run_cmd("adb shell input keyevent 4")
        else:
            run_cmd("adb shell input keyevent 3")
        time.sleep(0.05) # very short delay to catch layout transition
        r = run_annotated(i, f"Transition {'Back' if i%2==1 else 'Home'}")
        results.append(r)
        print(f"[{i:02d}/20] {r['context']}: ok={r['ok']} in {r['elapsed_ms']}ms, elements={r.get('elements_count', 0)}, img_size={r.get('image_bytes_approx', 0)}B (err={r.get('error')})")
        time.sleep(0.3)

    # Context 3: Chrome Browser (9-12)
    print("\n--- Phase 3: Chrome Browser (Tests 9-12) ---")
    run_cmd("adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main")
    time.sleep(2)
    for i in range(9, 13):
        r = run_annotated(i, "Chrome Browser")
        results.append(r)
        print(f"[{i:02d}/20] {r['context']}: ok={r['ok']} in {r['elapsed_ms']}ms, elements={r.get('elements_count', 0)}, img_size={r.get('image_bytes_approx', 0)}B (err={r.get('error')})")
        time.sleep(0.3)
        
    # Context 4: YouTube App (13-16)
    print("\n--- Phase 4: YouTube Rich Feed (Tests 13-16) ---")
    run_cmd("adb shell am start -n com.google.android.youtube/.app.honeycomb.Shell$HomeActivity")
    time.sleep(2)
    for i in range(13, 17):
        r = run_annotated(i, "YouTube Feed")
        results.append(r)
        print(f"[{i:02d}/20] {r['context']}: ok={r['ok']} in {r['elapsed_ms']}ms, elements={r.get('elements_count', 0)}, img_size={r.get('image_bytes_approx', 0)}B (err={r.get('error')})")
        time.sleep(0.3)

    # Context 5: Rapid-fire 4x in succession (17-20)
    print("\n--- Phase 5: Rapid-fire Back-to-Back Zero Sleep (Tests 17-20) ---")
    for i in range(17, 21):
        r = run_annotated(i, "Rapid-Fire")
        results.append(r)
        print(f"[{i:02d}/20] {r['context']}: ok={r['ok']} in {r['elapsed_ms']}ms, elements={r.get('elements_count', 0)}, img_size={r.get('image_bytes_approx', 0)}B (err={r.get('error')})")

    # Return to home
    run_cmd("adb shell input keyevent 3")
    
    print("\nPost-Test Memory:")
    for line in get_mem():
        print(" ", line)
        
    # Summary
    success_count = sum(1 for r in results if r['ok'])
    total = len(results)
    rate = (success_count / total) * 100
    print(f"\n=== SUMMARY ===")
    print(f"Total Tests: {total}")
    print(f"Passed: {success_count} ({rate:.1f}%)")
    print(f"Failed: {total - success_count}")
    
    with open("results_20x.json", "w") as f:
        json.dump(results, f, indent=2)

if __name__ == "__main__":
    main()
