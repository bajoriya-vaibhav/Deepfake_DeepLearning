"""
Simple Gradio interface for multimodal deepfake detection.
Supports: Video (audio+video), Image only, Audio only
With Gemini API integration and security-first mismatch detection.
"""
import gradio as gr
from detector import DeepFakeDetector
import logging
from PIL import Image
import numpy as np

logger = logging.getLogger(__name__)

# Initialize the detector
print("Loading models...")
detector = DeepFakeDetector()
print("Ready!")


# Video Analysis
def analyze_video_mode(video_path):
    """Analyze video with audio+visual cues and security checks."""
    if video_path is None:
        return "ERROR", 0.0, "Please upload a video", "", ""
    
    results = detector.analyze_video(video_path)
    verdict = results['verdict']
    confidence = results['confidence']
    video_score = results.get('video_fake_score', 0.0)
    audio_score = results.get('audio_fake_score', 0.0)
    analysis_source = results.get('analysis_source', 'local')
    
    # Check for security alerts
    security_alert = results.get('security_alert')
    threat_vector = results.get('threat_vector')
    
    info = f"Video: {video_score:.1%} | Audio: {audio_score:.1%}" if audio_score else f"Video: {video_score:.1%}"
    
    # Security warnings
    warning = ""
    if security_alert == "MISMATCH_DETECTED":
        if threat_vector == "FACE_SWAP_ATTACK":
            warning = "⚠️ ALERT: Possible face swap with real audio detected!"
        elif threat_vector == "VOICE_CLONE_ATTACK":
            warning = "⚠️ ALERT: Possible voice cloning with real video detected!"
        else:
            warning = "⚠️ ALERT: Suspicious mismatch between audio and video!"
    elif security_alert == "LOW_CONFIDENCE":
        warning = "⚠️ Low confidence - manual review recommended"
    
    # Analysis source badge
    source_badge = f"🤖 Analyzed by: {'Gemini API' if analysis_source == 'gemini' else 'Local Models'}"
    
    return verdict, confidence, info, warning, source_badge


# Image Analysis
def analyze_image_mode(image):
    """Analyze single image."""
    if image is None:
        return "ERROR", 0.0, "Please upload an image", ""
    
    # Convert numpy array to PIL Image if needed
    if isinstance(image, np.ndarray):
        image = Image.fromarray(image.astype('uint8'), 'RGB')
    
    results = detector.analyze_image(image)
    verdict = results['verdict']
    confidence = results['confidence']
    fake_prob = results['fake_probability']
    analysis_source = results.get('analysis_source', 'local')
    
    info = f"Fake probability: {fake_prob:.1%}"
    source_badge = f"🤖 Analyzed by: {'Gemini API' if analysis_source == 'gemini' else 'Local Model'}"
    
    return verdict, confidence, info, source_badge


# Audio Analysis
def analyze_audio_mode(audio_path):
    """Analyze audio only."""
    if audio_path is None:
        return "ERROR", 0.0, "Please upload an audio file"
    
    results = detector.analyze_audio(audio_path)
    verdict = results['verdict']
    confidence = results['confidence']
    fake_prob = results['fake_probability']
    
    info = f"Fake probability: {fake_prob:.1%}"
    
    return verdict, confidence, info


# Build interface
with gr.Blocks(title="DeepFake Detector", theme=gr.themes.Soft()) as app:
    gr.Markdown("# 🔍 DeepFake Detector")
    gr.Markdown("### Powered by Gemini API + Local Models with Security-First Analysis")
    
    with gr.Tabs():
        # Video Tab
        with gr.Tab("🎥 Video (Audio + Visual)"):
            with gr.Row():
                video_input = gr.Video(label="Upload Video")
                with gr.Column():
                    video_verdict = gr.Textbox(label="Verdict", interactive=False)
                    video_confidence = gr.Number(label="Confidence", interactive=False)
                    video_info = gr.Textbox(label="Details", interactive=False)
                    video_warning = gr.Textbox(label="Security Alert", interactive=False)
                    video_source = gr.Textbox(label="Analysis Source", interactive=False)
            video_btn = gr.Button("🔎 Analyze Video", variant="primary", size="lg")
            video_btn.click(
                fn=analyze_video_mode,
                inputs=video_input,
                outputs=[video_verdict, video_confidence, video_info, video_warning, video_source]
            )
            with gr.Accordion("Security Features", open=False):
                gr.Markdown("""
                - ✅ Detects face swap attacks (fake video + real audio)
                - ✅ Detects voice cloning attacks (real video + fake audio)
                - ✅ Conservative approach: flags suspicious mismatches
                - ✅ Automatic fallback if Gemini API unavailable
                """)
        
        # Image Tab
        with gr.Tab("🖼️ Image Only"):
            with gr.Row():
                image_input = gr.Image(label="Upload Image", type="numpy")
                with gr.Column():
                    image_verdict = gr.Textbox(label="Verdict", interactive=False)
                    image_confidence = gr.Number(label="Confidence", interactive=False)
                    image_info = gr.Textbox(label="Details", interactive=False)
                    image_source = gr.Textbox(label="Analysis Source", interactive=False)
            image_btn = gr.Button("🔎 Analyze Image", variant="primary", size="lg")
            image_btn.click(
                fn=analyze_image_mode,
                inputs=image_input,
                outputs=[image_verdict, image_confidence, image_info, image_source]
            )
        
        # Audio Tab
        with gr.Tab("🎵 Audio Only"):
            with gr.Row():
                audio_input = gr.Audio(label="Upload Audio", type="filepath")
                with gr.Column():
                    audio_verdict = gr.Textbox(label="Verdict", interactive=False)
                    audio_confidence = gr.Number(label="Confidence", interactive=False)
                    audio_info = gr.Textbox(label="Details", interactive=False)
            audio_btn = gr.Button("🔎 Analyze Audio", variant="primary", size="lg")
            audio_btn.click(
                fn=analyze_audio_mode,
                inputs=audio_input,
                outputs=[audio_verdict, audio_confidence, audio_info]
            )


if __name__ == "__main__":
    app.launch(debug=True)
