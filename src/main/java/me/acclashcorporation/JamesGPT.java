package me.acclashcorporation;

// [START speech_transcribe_infinite_streaming]

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1p1beta1.*;
import com.google.cloud.texttospeech.v1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.theokanning.openai.OpenAiApi;
import com.theokanning.openai.OpenAiService;
import com.theokanning.openai.completion.CompletionRequest;
import me.acclashcorporation.utils.FineTune;
import me.acclashcorporation.utils.SampleTTS;

import javax.sound.sampled.*;
import javax.sound.sampled.DataLine.Info;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class JamesGPT {

    private static final int STREAMING_LIMIT = 290000; // ~5 minutes

    public static final String RED = "\033[0;31m";
    public static final String GREEN = "\033[0;32m";
    public static final String YELLOW = "\033[0;33m";

    // Creating shared object
    private static volatile BlockingQueue<byte[]> sharedQueue = new LinkedBlockingQueue();
    private static TargetDataLine targetDataLine;
    private static int BYTES_PER_BUFFER = 6400; // buffer size in bytes

    private static int restartCounter = 0;
    private static ArrayList<ByteString> audioInput = new ArrayList<ByteString>();
    private static ArrayList<ByteString> lastAudioInput = new ArrayList<ByteString>();
    private static int resultEndTimeInMS = 0;
    private static int isFinalEndTime = 0;
    private static int finalRequestEndTime = 0;
    private static boolean newStream = true;
    private static double bridgingOffset = 0;
    private static boolean lastTranscriptWasFinal = false;
    private static StreamController referenceToStreamController;
    private static ByteString tempByteString;

    private static StringBuilder conversation = new StringBuilder();

    public static void main(String[] args) throws Exception {
        //FineTune.fineTune();
        startConversation();
        //SampleTTS.sampleTest();
    }

    public static String convertMillisToDate(double milliSeconds) {
        long millis = (long) milliSeconds;
        DecimalFormat format = new DecimalFormat();
        format.setMinimumIntegerDigits(2);
        return String.format(
                "%s:%s /",
                format.format(TimeUnit.MILLISECONDS.toMinutes(millis)),
                format.format(
                        TimeUnit.MILLISECONDS.toSeconds(millis)
                                - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))));
    }

    /**
     * Performs infinite streaming speech recognition
     */
    public static void infiniteStreamingRecognize() throws Exception {

        // Microphone Input buffering
        class MicBuffer implements Runnable {

            @Override
            public void run() {
                System.out.println(YELLOW);
                System.out.println("Start speaking...Press Ctrl-C to stop");
                targetDataLine.start();
                byte[] data = new byte[BYTES_PER_BUFFER];
                while (targetDataLine.isOpen()) {
                    try {
                        int numBytesRead = targetDataLine.read(data, 0, data.length);
                        if ((numBytesRead <= 0) && (targetDataLine.isOpen())) {
                            continue;
                        }
                        sharedQueue.put(data.clone());
                    } catch (InterruptedException e) {
                        System.out.println("Microphone input buffering interrupted : " + e.getMessage());
                    }
                }
            }
        }

        // Creating microphone input buffer thread
        MicBuffer micrunnable = new MicBuffer();
        Thread micThread = new Thread(micrunnable);
        ResponseObserver<StreamingRecognizeResponse> responseObserver = null;
        try (SpeechClient client = SpeechClient.create()) {
            ClientStream<StreamingRecognizeRequest> clientStream;
            responseObserver =
                    new ResponseObserver<StreamingRecognizeResponse>() {

                        ArrayList<StreamingRecognizeResponse> responses = new ArrayList<>();

                        public void onStart(StreamController controller) {
                            referenceToStreamController = controller;
                        }

                        public void onResponse(StreamingRecognizeResponse response) {
                            responses.add(response);
                            StreamingRecognitionResult result = response.getResultsList().get(0);
                            Duration resultEndTime = result.getResultEndTime();
                            resultEndTimeInMS =
                                    (int)
                                            ((resultEndTime.getSeconds() * 1000) + (resultEndTime.getNanos() / 1000000));
                            double correctedTime =
                                    resultEndTimeInMS - bridgingOffset + (STREAMING_LIMIT * restartCounter);

                            SpeechRecognitionAlternative alternative = result.getAlternativesList().get(0);
                            if (result.getIsFinal()) {
                                System.out.print(GREEN);
                                System.out.print("\033[2K\r");
                                System.out.printf(
                                        "%s: %s [confidence: %.2f]\n",
                                        convertMillisToDate(correctedTime),
                                        alternative.getTranscript(),
                                        alternative.getConfidence());
                                isFinalEndTime = resultEndTimeInMS;
                                lastTranscriptWasFinal = true;

                                // Handle human's response
                                if (alternative.getTranscript().toLowerCase().contains("james") || conversation.toString().endsWith("?")) {
                                    addMessage(alternative.getTranscript());
                                    try {
                                        getResponse();
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            } else {
                                System.out.print(RED);
                                System.out.print("\033[2K\r");
                                System.out.printf(
                                        "%s: %s", convertMillisToDate(correctedTime), alternative.getTranscript());
                                lastTranscriptWasFinal = false;
                            }
                        }

                        public void onComplete() {}

                        public void onError(Throwable t) {}
                    };
            clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

            //ArrayList<String> languageList = new ArrayList<>();
            //languageList.add("de-DE");
            //languageList.add("es-MX");

            RecognitionConfig recognitionConfig =
                    RecognitionConfig.newBuilder()
                            .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                            .setLanguageCode("en-US")
                            //.addAllAlternativeLanguageCodes(languageList)
                            .setSampleRateHertz(16000)
                            .build();

            StreamingRecognitionConfig streamingRecognitionConfig =
                    StreamingRecognitionConfig.newBuilder()
                            .setConfig(recognitionConfig)
                            .setInterimResults(true)
                            .build();

            StreamingRecognizeRequest request =
                    StreamingRecognizeRequest.newBuilder()
                            .setStreamingConfig(streamingRecognitionConfig)
                            .build(); // The first request in a streaming call has to be a config

            clientStream.send(request);

            try {
                // SampleRate:16000Hz, SampleSizeInBits: 16, Number of channels: 1, Signed: true,
                // bigEndian: false
                AudioFormat audioFormat = new AudioFormat(16000, 16, 1, true, false);
                DataLine.Info targetInfo =
                        new Info(
                                TargetDataLine.class,
                                audioFormat); // Set the system information to read from the microphone audio
                // stream

                if (!AudioSystem.isLineSupported(targetInfo)) {
                    System.out.println("Microphone not supported");
                    System.exit(0);
                }
                // Target data line captures the audio stream the microphone produces.
                targetDataLine = (TargetDataLine) AudioSystem.getLine(targetInfo);
                targetDataLine.open(audioFormat);
                micThread.start();

                long startTime = System.currentTimeMillis();

                while (true) {

                    long estimatedTime = System.currentTimeMillis() - startTime;

                    if (estimatedTime >= STREAMING_LIMIT) {

                        clientStream.closeSend();
                        referenceToStreamController.cancel(); // remove Observer

                        if (resultEndTimeInMS > 0) {
                            finalRequestEndTime = isFinalEndTime;
                        }
                        resultEndTimeInMS = 0;

                        lastAudioInput = null;
                        lastAudioInput = audioInput;
                        audioInput = new ArrayList<ByteString>();

                        restartCounter++;

                        if (!lastTranscriptWasFinal) {
                            System.out.print('\n');
                        }

                        newStream = true;

                        clientStream = client.streamingRecognizeCallable().splitCall(responseObserver);

                        request =
                                StreamingRecognizeRequest.newBuilder()
                                        .setStreamingConfig(streamingRecognitionConfig)
                                        .build();

                        System.out.println(YELLOW);
                        System.out.printf("%d: RESTARTING REQUEST\n", restartCounter * STREAMING_LIMIT);

                        startTime = System.currentTimeMillis();

                    } else {

                        if ((newStream) && (lastAudioInput.size() > 0)) {
                            // if this is the first audio from a new request
                            // calculate amount of unfinalized audio from last request
                            // resend the audio to the speech client before incoming audio
                            double chunkTime = STREAMING_LIMIT / lastAudioInput.size();
                            // ms length of each chunk in previous request audio arrayList
                            if (chunkTime != 0) {
                                if (bridgingOffset < 0) {
                                    // bridging Offset accounts for time of resent audio
                                    // calculated from last request
                                    bridgingOffset = 0;
                                }
                                if (bridgingOffset > finalRequestEndTime) {
                                    bridgingOffset = finalRequestEndTime;
                                }
                                int chunksFromMs =
                                        (int) Math.floor((finalRequestEndTime - bridgingOffset) / chunkTime);
                                // chunks from MS is number of chunks to resend
                                bridgingOffset =
                                        (int) Math.floor((lastAudioInput.size() - chunksFromMs) * chunkTime);
                                // set bridging offset for next request
                                for (int i = chunksFromMs; i < lastAudioInput.size(); i++) {
                                    request =
                                            StreamingRecognizeRequest.newBuilder()
                                                    .setAudioContent(lastAudioInput.get(i))
                                                    .build();
                                    clientStream.send(request);
                                }
                            }
                            newStream = false;
                        }

                        tempByteString = ByteString.copyFrom(sharedQueue.take());

                        request =
                                StreamingRecognizeRequest.newBuilder().setAudioContent(tempByteString).build();

                        audioInput.add(tempByteString);
                    }

                    clientStream.send(request);
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }

    public static void startConversation() throws Exception {
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File("startup.wav").getAbsoluteFile());
        Clip clip = AudioSystem.getClip();
        clip.open(audioInputStream);
        clip.start();
        conversation = new StringBuilder("""
                The following is a conversation with an AI assistant. The assistant is helpful, creative, clever, and very friendly. The assistant's name is James. He was created by AC Clash.
                AI: I am an AI created by AC Clash. How can I help you today?
                Human: Hi, who are you?
                AI:""");
        getResponse();
        try {
            infiniteStreamingRecognize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void getResponse() throws Exception {
        OpenAiService service = new OpenAiService("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", 0);
        CompletionRequest request = CompletionRequest.builder()
                .prompt(conversation.toString())
                .model("gpt-4") //Use the latest davinci model
                .temperature(0.90) //How creative the AI should be
                .maxTokens(150) //How many tokens the AI should generate. Tokens are words, punctuation, etc.
                .topP(1.0) //How much diversity the AI should have. 1.0 is the most diverse
                .frequencyPenalty(0.0) //How much the AI should avoid repeating itself
                .presencePenalty(0.6) //How much the AI should avoid repeating the same words
                .stop(List.of("Human:", "AI:")) //Stop the AI from generating more text when it sees these words
                .build();
        var choices = service.createCompletion(request).getChoices();
        var response = choices.get(0).getText(); //what the AI responds with
        conversation.append(response.stripLeading());
        System.out.print(GREEN);
        System.out.println(response.stripLeading());
        speak(response.stripLeading());
    }

    public static void addMessage(String transcript) {
        conversation.append("\nHuman:").append(transcript).append("\nAI:");
    }

    public static void speak(String output) throws Exception {
        // Instantiates a client
        try (TextToSpeechClient textToSpeechClient = TextToSpeechClient.create()) {
            // Set the text input to be synthesized
            SynthesisInput input = SynthesisInput.newBuilder().setText(output).build();

            // Build the voice request, select the language code ("en-US") and the ssml voice gender
            // ("neutral")
            VoiceSelectionParams voice =
                    VoiceSelectionParams.newBuilder()
                            .setLanguageCode("en-GB")
                            .setName("en-GB-Neural2-B")
                            .setSsmlGender(SsmlVoiceGender.MALE)
                            .build();

            // Select the type of audio file you want returned
            AudioConfig audioConfig =
                    AudioConfig.newBuilder().setAudioEncoding(AudioEncoding.LINEAR16).build();

            // Perform the text-to-speech request on the text input with the selected voice parameters and
            // audio file type
            SynthesizeSpeechResponse response =
                    textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);

            // Get the audio contents from the response
            ByteString audioContents = response.getAudioContent();

            // Write the response to the output file.
            try (OutputStream out = new FileOutputStream("output.wav")) {
                out.write(audioContents.toByteArray());
                //System.out.println("Audio content written to file \"output.wav\"");
            }
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new File("output.wav").getAbsoluteFile());
            Clip clip = AudioSystem.getClip();
            clip.open(audioInputStream);
            clip.start();
        }
    }

}
// [END speech_transcribe_infinite_streaming]
