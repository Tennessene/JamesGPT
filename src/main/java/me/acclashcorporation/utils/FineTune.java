package me.acclashcorporation.utils;

import com.theokanning.openai.OpenAiService;
import com.theokanning.openai.finetune.FineTuneRequest;

import java.util.Date;

public class FineTune {

    public static void fineTune() {
        OpenAiService service = new OpenAiService("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", 0);
        FineTuneRequest fineTuneRequest = FineTuneRequest.builder()
                .trainingFile("fine-tune.jsonl")
                .model("davinci")
                .suffix("james")
                .build();
        var finalRequest = service.createFineTune(fineTuneRequest);
        Date createDate = new Date(finalRequest.getCreatedAt());
        System.out.println(finalRequest.getFineTunedModel() + " was created at " + createDate);
    }
}
