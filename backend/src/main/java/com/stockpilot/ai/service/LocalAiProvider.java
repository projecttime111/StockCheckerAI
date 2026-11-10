package com.stockpilot.ai.service;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Function;

/** Honest deterministic fallback; uses exactly the same authorized tool boundary as hosted AI. */
@Component
public class LocalAiProvider implements AiProvider {
    @Override public String name(){return "local";}
    @Override public Completion complete(List<Turn> history,String prompt,List<Tool> tools,Function<Call,Map<String,Object>> execute){
        String lower=prompt.toLowerCase(Locale.ROOT); String tool;
        if(lower.matches("(?s)^(and |what about |same |instead |last month).*"))
            for(int i=history.size()-1;i>=0;i--)if(history.get(i).role().equals("USER")){lower=history.get(i).content().toLowerCase(Locale.ROOT)+" "+lower;break;}
        if(lower.contains("supplier")&&lower.contains("margin")) tool="supplier_margin";
        else if(lower.contains("margin")||lower.contains("profit")||lower.contains("drop")||lower.contains("decrease")) tool="profit_comparison";
        else if(lower.contains("reorder")) tool="reorder";
        else if(lower.contains("outstanding")||lower.contains("receivable")) tool="receivables";
        else if(lower.contains("dead")||lower.contains("slow")||lower.contains("not moving")) tool="dead_stock";
        else if(lower.contains("forecast")||lower.contains("stockout")||lower.contains("stock out")) tool="forecast";
        else if(lower.contains("cleanup")||lower.contains("quality")||lower.contains("missing")) tool="data_quality";
        else if(lower.contains("stock")||lower.contains("warehouse")) tool="inventory";
        else tool="summary";
        if(tools.stream().noneMatch(t->t.name().equals(tool))) return new Completion("You do not have permission to access this information.",name(),null,0,0);
        Map<String,Object> args=Map.of();
        if(tool.equals("profit_comparison")&&(lower.contains("last month")||lower.contains("previous month"))) {
            var end=java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).minusDays(1);
            args=Map.of("from",end.withDayOfMonth(1).toString(),"through",end.toString());
        }
        var evidence=execute.apply(new Call(tool,args));
        String answer=String.valueOf(evidence.getOrDefault("summary","The requested information is available in the evidence card."));
        return new Completion(answer,name(),null,0,0);
    }
}
