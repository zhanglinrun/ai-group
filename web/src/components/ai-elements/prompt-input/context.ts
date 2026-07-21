import { createContext, useContext, type RefObject } from 'react';

import type { PromptInputAttachmentItem } from './types';

export type AttachmentsContext = {
  files: PromptInputAttachmentItem[];
  add: (files: File[] | FileList) => void;
  remove: (id: string) => void;
  clear: () => void;
  openFileDialog: () => void;
  fileInputRef: RefObject<HTMLInputElement | null>;
};

export type TextInputContext = {
  value: string;
  setInput: (value: string) => void;
  clear: () => void;
};

export type PromptInputControllerProps = {
  textInput: TextInputContext;
  attachments: AttachmentsContext;
  /** INTERNAL: Allows PromptInput to register its file input and open callback. */
  __registerFileInput: (ref: RefObject<HTMLInputElement | null>, open: () => void) => void;
};

export const PromptInputController = createContext<PromptInputControllerProps | null>(null);
export const ProviderAttachmentsContext = createContext<AttachmentsContext | null>(null);
export const LocalAttachmentsContext = createContext<AttachmentsContext | null>(null);

export const usePromptInputController = () => {
  const context = useContext(PromptInputController);
  if (!context) {
    throw new Error(
      'Wrap your component inside <PromptInputProvider> to use usePromptInputController().',
    );
  }
  return context;
};

export const useOptionalPromptInputController = () => useContext(PromptInputController);

export const useProviderAttachments = () => {
  const context = useContext(ProviderAttachmentsContext);
  if (!context) {
    throw new Error(
      'Wrap your component inside <PromptInputProvider> to use useProviderAttachments().',
    );
  }
  return context;
};

export const useOptionalProviderAttachments = () => useContext(ProviderAttachmentsContext);

export const usePromptInputAttachments = () => {
  const provider = useOptionalProviderAttachments();
  const local = useContext(LocalAttachmentsContext);
  const context = provider ?? local;
  if (!context) {
    throw new Error(
      'usePromptInputAttachments must be used within a PromptInput or PromptInputProvider',
    );
  }
  return context;
};
