package net.resheim.timekeeper.server;

import java.util.List;
import java.util.Random;

/** Deterministic English/Norwegian prose, links, tasks and technical code. */
final class NotesSearchCorpus {
    private NotesSearchCorpus(){}
    static String markdown(int number){
        var random=new Random(number);var text=new StringBuilder(66_000);
        text.append("---\nprivate-fixture: excludedmetadata\n---\n\n# Service record ").append(number).append("\n\nCache misses increase latency during a rolling restart.\n\n");
        var fragments=List.of(
                "The next deployment needs a reviewed rollback plan. Compare the published schema with the previous release and keep a record of the acceptance checks.\n\n",
                "Feilsøking av køer: undersøk tidsavbrudd, vedvarende lagring og gjenoppretting. Brukerens tidssone bestemmer hvilken arbeidsdag hendelsen tilhører.\n\n",
                "- [ ] Check retry safety\n- [x] Verify account isolation\n- Inspect the service.cluster.local endpoint and the net.resheim.timekeeper package.\n\n",
                "~~~yaml\napiVersion: v1\nkind: Pod\nmetadata:\n  name: dnsutils\nspec:\n  restartPolicy: Always\n~~~\n\n",
                "~~~shell\nkubectl exec dnsutils -- nslookup service.cluster.local\ncurl -H 'Content-Type: application/json' https://example.invalid/status\n~~~\n\n",
                "See [the incident record](Notes/Incident.md) and [[Operations/Runbook]] before changing the configuration. Keep foo_bar and HTTP/2 identifiers searchable.\n\n",
                "> Recovery must preserve exact bytes. A rejected concurrent save keeps the existing note and its modification time.\n\n",
                "## Observations\n\nThe database committed the change before either replica refreshed its derived index. A new process must replay that durable state.\n\n");
        while(text.length()<65_536){
            text.append(fragments.get(random.nextInt(fragments.size())));
            if(random.nextInt(8)==0)text.append("Request build-").append(number).append('-').append(random.nextInt(1_000_000)).append(" completed with status ").append(200+random.nextInt(6)).append(".\n\n");
        }
        return text.append("\nEnd marker ").append(number).append(".\n").toString();
    }
}
