from google import generativeai as genai
genai.configure(api_key="AIzaSyCSz7HeKZI3YVKqortMu_MRGdXbPY06nS4")

for m in genai.list_models():
    print(m.name, m.supported_generation_methods)
